package com.posthog.server.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogOnFeatureFlags
import com.posthog.internal.FeatureFlag
import com.posthog.internal.FlagDefinition
import com.posthog.internal.PostHogApi
import com.posthog.internal.PostHogFeatureFlagsInterface
import com.posthog.internal.PropertyGroup

internal enum class EvaluationSource {
    LOCAL,
    REMOTE,
    CACHE,
}

internal data class FeatureFlagResolutionParameters(
    val groups: Map<String, String>? = null,
    val personProperties: Map<String, Any?>? = null,
    val groupProperties: Map<String, Map<String, Any?>>? = null,
    val onlyEvaluateLocally: Boolean = false,
)

internal data class FeatureFlagResultContext(
    val results: Map<String, FeatureFlag>? = null,
    val source: EvaluationSource? = null,
    val requestId: String? = null,
    val exhaustive: Boolean = false,
    val parameters: FeatureFlagResolutionParameters? = null,
)

internal data class RemoteFeatureFlagsResponse(
    val flags: Map<String, FeatureFlag>?,
    val requestId: String?,
)

internal class PostHogFeatureFlags(
    private val config: PostHogConfig,
    private val api: PostHogApi,
    private val cacheMaxAgeMs: Int,
    private val cacheMaxSize: Int,
    private val localEvaluation: Boolean = false,
    private val personalApiKey: String? = null,
    private val pollIntervalSeconds: Int = 30,
    private val onFeatureFlags: PostHogOnFeatureFlags? = null,
) : PostHogFeatureFlagsInterface {
    private val cache =
        PostHogFeatureFlagCache(
            maxSize = cacheMaxSize,
            maxAgeMs = cacheMaxAgeMs,
        )

    @Volatile
    private var featureFlags: List<FlagDefinition>? = null

    @Volatile
    private var flagDefinitions: Map<String, FlagDefinition>? = null

    @Volatile
    private var cohorts: Map<String, PropertyGroup>? = null

    @Volatile
    private var groupTypeMapping: Map<String, String>? = null

    private val evaluator: FlagEvaluator = FlagEvaluator(config)

    private var poller: LocalEvaluationPoller? = null

    private var definitionsLoaded = false

    init {
        startPoller()
        if (!localEvaluation) {
            onFeatureFlags?.loaded()
        }
    }

    override fun getFeatureFlag(
        key: String,
        defaultValue: Any?,
        distinctId: String?,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): Any? {
        if (distinctId == null) {
            return defaultValue
        }
        val flag =
            resolveFeatureFlag(
                key,
                distinctId,
                groups,
                personProperties,
                groupProperties,
            )?.results?.get(key)
        return flag?.variant ?: flag?.enabled ?: defaultValue
    }

    override fun getFeatureFlagPayload(
        key: String,
        defaultValue: Any?,
        distinctId: String?,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): Any? {
        if (distinctId == null) {
            return defaultValue
        }
        return resolveFeatureFlag(
            key,
            distinctId,
            groups,
            personProperties,
            groupProperties,
        )?.results?.get(key)?.metadata?.payload
            ?: defaultValue
    }

    internal fun resolveFeatureFlag(
        key: String,
        distinctId: String,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
        onlyEvaluateLocally: Boolean = false,
    ): FeatureFlagResultContext? {
        val cachedFlags =
            getFeatureFlagsFromCache(distinctId, groups, personProperties, groupProperties)
        if (cachedFlags != null) {
            config.logger.log("Feature flags cache hit for distinctId: $distinctId")
            val flag = cachedFlags[key]
            if (flag != null) {
                return FeatureFlagResultContext(
                    results = mapOf(key to flag),
                    source = EvaluationSource.CACHE,
                    parameters =
                        FeatureFlagResolutionParameters(
                            groups = groups,
                            personProperties = personProperties,
                            groupProperties = groupProperties,
                            onlyEvaluateLocally = onlyEvaluateLocally,
                        ),
                )
            }
        }

        if (localEvaluation) {
            if (flagDefinitions == null && !definitionsLoaded) {
                config.logger.log("Flag definitions not loaded, loading now")
                loadFeatureFlagDefinitions()
            }

            val flagDef = flagDefinitions?.get(key)
            if (flagDef != null) {
                try {
                    config.logger.log("Attempting local evaluation for flag '$key' for distinctId: $distinctId")
                    val props = (personProperties ?: emptyMap()).toMutableMap()

                    val result =
                        computeFlagLocally(
                            key = key,
                            distinctId = distinctId,
                            personProperties = props,
                            groups = groups,
                            groupProperties = groupProperties,
                        )

                    val flag = buildFeatureFlagFromResult(key, result, flagDef)
                    config.logger.log("Local evaluation successful for flag '$key'")
                    return FeatureFlagResultContext(
                        results = mapOf(key to flag),
                        source = EvaluationSource.LOCAL,
                        parameters =
                            FeatureFlagResolutionParameters(
                                groups = groups,
                                personProperties = personProperties,
                                groupProperties = groupProperties,
                                onlyEvaluateLocally = onlyEvaluateLocally,
                            ),
                    )
                } catch (e: InconclusiveMatchException) {
                    config.logger.log("Local evaluation inconclusive for flag '$key': ${e.message}")
                    if (onlyEvaluateLocally) {
                        return null
                    }
                    // Fall through to remote evaluation
                } catch (e: Throwable) {
                    config.logger.log("Local evaluation failed for flag '$key': ${e.message}")
                    if (onlyEvaluateLocally) {
                        return null
                    }
                    // Fall through to remote evaluation
                }
            }
        } else if (onlyEvaluateLocally) {
            return null
        }

        // Local evaluation not available or failed - fall back to API
        // Fetch and cache all flags, then return the specific one
        config.logger.log("Feature flag cache miss for distinctId: $distinctId, calling API")
        val remoteFlags =
            getFeatureFlagsFromRemote(
                distinctId,
                groups,
                personProperties,
                groupProperties,
            )
        if (remoteFlags.flags != null) {
            val flag = remoteFlags.flags[key]
            if (flag != null) {
                return FeatureFlagResultContext(
                    results = mapOf(key to flag),
                    source = EvaluationSource.REMOTE,
                    requestId = remoteFlags.requestId,
                    parameters =
                        FeatureFlagResolutionParameters(
                            groups = groups,
                            personProperties = personProperties,
                            groupProperties = groupProperties,
                            onlyEvaluateLocally = onlyEvaluateLocally,
                        ),
                )
            }
        }
        return null
    }

    private fun getFeatureFlagsFromCache(
        distinctId: String,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): Map<String, FeatureFlag>? {
        val cacheKey =
            FeatureFlagCacheKey(
                distinctId = distinctId,
                groups = groups,
                personProperties = personProperties,
                groupProperties = groupProperties,
            )

        return cache.get(cacheKey)
    }

    private fun getFeatureFlagsFromLocalEvaluation(
        distinctId: String,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
        onlyEvaluateLocally: Boolean = false,
    ): Map<String, FeatureFlag>? {
        if (!localEvaluation) {
            return null
        }

        if (flagDefinitions == null && !definitionsLoaded) {
            config.logger.log("Flag definitions not loaded, loading now")
            loadFeatureFlagDefinitions()
        }

        val currentFlagDefinitions = flagDefinitions
        if (currentFlagDefinitions == null) {
            return null
        }

        config.logger.log("Attempting local evaluation for distinctId: $distinctId")
        val localFlags = mutableMapOf<String, FeatureFlag>()
        val props = (personProperties ?: emptyMap()).toMutableMap()

        // Evaluate all flags locally
        for ((key, flagDef) in currentFlagDefinitions) {
            try {
                val result =
                    computeFlagLocally(
                        key = key,
                        distinctId = distinctId,
                        personProperties = props,
                        groups = groups,
                        groupProperties = groupProperties,
                    )

                localFlags[key] = buildFeatureFlagFromResult(key, result, flagDef)
            } catch (e: InconclusiveMatchException) {
                config.logger.log("Local evaluation inconclusive for flag '$key': ${e.message}")
                if (!onlyEvaluateLocally) {
                    // Allow fallback to remote evaluation
                    return null
                }
            }
        }

        config.logger.log("Local evaluation successful for ${localFlags.size} flags")
        return localFlags
    }

    private fun getFeatureFlagsFromRemote(
        distinctId: String,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): RemoteFeatureFlagsResponse {
        val cacheKey =
            FeatureFlagCacheKey(
                distinctId = distinctId,
                groups = groups,
                personProperties = personProperties,
                groupProperties = groupProperties,
            )

        val cachedFlags = cache.get(cacheKey)
        if (cachedFlags != null) {
            return RemoteFeatureFlagsResponse(flags = cachedFlags, requestId = null)
        }

        return try {
            val response = api.flags(distinctId, null, groups, personProperties, groupProperties)
            val flags = response?.flags
            cache.put(cacheKey, flags)
            RemoteFeatureFlagsResponse(flags = flags, requestId = response?.requestId)
        } catch (e: Throwable) {
            config.logger.log("Loading remote feature flags failed: $e")
            RemoteFeatureFlagsResponse(flags = null, requestId = null)
        }
    }

    override fun getFeatureFlags(
        distinctId: String?,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): Map<String, FeatureFlag>? {
        val result =
            resolveFeatureFlags(
                distinctId,
                groups,
                personProperties,
                groupProperties,
            )
        return result?.results
    }

    internal fun resolveFeatureFlags(
        distinctId: String?,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
        onlyEvaluateLocally: Boolean = false,
    ): FeatureFlagResultContext? {
        if (distinctId == null) {
            config.logger.log("getFeatureFlags called but no distinctId available for API call")
            return null
        }

        val cached = getFeatureFlagsFromCache(distinctId, groups, personProperties, groupProperties)
        if (cached != null) {
            return FeatureFlagResultContext(
                results = cached,
                source = EvaluationSource.CACHE,
                exhaustive = true,
            )
        }

        // If no cached flags, try local evaluation
        val localFlags =
            getFeatureFlagsFromLocalEvaluation(
                distinctId,
                groups,
                personProperties,
                groupProperties,
                onlyEvaluateLocally = onlyEvaluateLocally,
            )
        if (localFlags != null) {
            return FeatureFlagResultContext(
                results = localFlags,
                source = EvaluationSource.LOCAL,
                exhaustive = true,
            )
        }

        // Finally, fall back to remote fetch
        val result =
            getFeatureFlagsFromRemote(distinctId, groups, personProperties, groupProperties)
        if (result.flags != null) {
            return FeatureFlagResultContext(
                results = result.flags,
                source = EvaluationSource.REMOTE,
                requestId = result.requestId,
                exhaustive = true,
            )
        }

        // Everything failed
        return null
    }

    override fun clear() {
        cache.clear()
        config.logger.log("Feature flags cache cleared")
    }

    override fun shutDown() {
        stopPoller()
    }

    /**
     * Load feature flag definitions from the API for local evaluation
     */
    private fun loadFeatureFlagDefinitions() {
        if (!localEvaluation || personalApiKey == null) {
            return
        }

        synchronized(this) {
            if (definitionsLoaded) {
                config.logger.log("Definitions already loaded, skipping")
                return
            }

            try {
                config.logger.log("Loading feature flags for local evaluation")
                val apiResponse = api.localEvaluation(personalApiKey)

                if (apiResponse != null) {
                    // apiResponse is now LocalEvaluationResponse with properly typed models
                    featureFlags = apiResponse.flags
                    flagDefinitions = apiResponse.flags?.associateBy { it.key }
                    cohorts = apiResponse.cohorts
                    groupTypeMapping = apiResponse.groupTypeMapping

                    config.logger.log("Loaded ${apiResponse.flags?.size ?: 0} feature flags for local evaluation")

                    definitionsLoaded = true
                    try {
                        onFeatureFlags?.loaded()
                    } catch (e: Throwable) {
                        config.logger.log("Error in onFeatureFlags callback: ${e.message}")
                    }
                }
            } catch (e: Throwable) {
                config.logger.log("Failed to load feature flags for local evaluation: ${e.message}")
            }
        }
    }

    /**
     * Convert evaluation result to FeatureFlag object
     */
    private fun buildFeatureFlagFromResult(
        key: String,
        result: Any?,
        flagDef: FlagDefinition,
    ): FeatureFlag {
        val (enabled, variant) =
            when (result) {
                is String -> true to result
                is Boolean -> result to null
                else -> false to null
            }

        val payload =
            if (result != null) {
                flagDef.filters.payloads?.get(result.toString())?.toString()
            } else {
                null
            }

        return FeatureFlag(
            key = key,
            enabled = enabled,
            variant = variant,
            metadata =
                com.posthog.internal.FeatureFlagMetadata(
                    id = flagDef.id,
                    payload = payload,
                    version = flagDef.version,
                ),
            reason = null,
        )
    }

    /**
     * Start the poller for local evaluation if enabled
     */
    private fun startPoller() {
        if (!localEvaluation) {
            return
        }

        if (personalApiKey == null) {
            config.logger.log("Local evaluation enabled but no personal API key provided")
            return
        }

        synchronized(this) {
            if (poller == null) {
                poller =
                    LocalEvaluationPoller(
                        config = config,
                        pollIntervalSeconds = pollIntervalSeconds,
                        execute = { loadFeatureFlagDefinitions() },
                    )
                poller?.start()
            }
        }
    }

    /**
     * Stop the local evaluation poller if it is running
     */
    private fun stopPoller() {
        synchronized(this) {
            poller?.stop()
            poller = null
        }
    }

    /**
     * Appends feature flag properties to event properties
     */
    internal fun appendFlagEventProperties(
        distinctId: String,
        properties: MutableMap<String, Any>?,
        groups: Map<String, String>?,
        options: com.posthog.server.PostHogSendFeatureFlagOptions?,
    ) {
        if (options == null || properties == null) {
            return
        }

        val response =
            resolveFeatureFlags(
                distinctId,
                groups,
                options.personProperties,
                options.groupProperties,
                options.onlyEvaluateLocally,
            )

        response?.results?.values?.let {
            val activeFeatureFlags = mutableListOf<String>()
            it.forEach { flag ->
                val flagValue = flag.variant ?: flag.enabled
                properties["\$feature/${flag.key}"] = flagValue
                if (flagValue != false) {
                    activeFeatureFlags.add(flag.key)
                }
            }
            properties["\$active_feature_flags"] = activeFeatureFlags.toList()
        }
    }

    /**
     * Compute a flag locally using the evaluation engine
     */
    private fun computeFlagLocally(
        key: String,
        distinctId: String,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): Any? {
        val flags = this.flagDefinitions ?: return null
        val flag = flags[key] ?: return null

        if (!flag.active) {
            return false
        }

        // Check if this is a group-based flag
        val aggregationGroupIndex = flag.filters.aggregationGroupTypeIndex

        val (evaluationId, evaluationProperties) =
            if (aggregationGroupIndex != null) {
                // Group-based flag - evaluate at group level
                val groupTypeName = groupTypeMapping?.get(aggregationGroupIndex.toString())

                if (groupTypeName == null) {
                    config.logger.log("Unknown group type index $aggregationGroupIndex for flag '$key'")
                    throw InconclusiveMatchException("Flag has unknown group type index")
                }

                val groupKey = groups?.get(groupTypeName)
                if (groupKey == null) {
                    // Group not provided - flag is off, don't failover to API
                    config.logger.log("Can't compute group flag '$key' without group '$groupTypeName'")
                    return false
                }

                // Use group's key and properties for evaluation
                Pair(groupKey, groupProperties)
            } else {
                // Person-based flag - use person's ID and properties
                Pair(distinctId, personProperties)
            }

        val evaluationCache = mutableMapOf<String, Any?>()
        return evaluator.matchFeatureFlagProperties(
            flag = flag,
            distinctId = evaluationId,
            properties = evaluationProperties ?: emptyMap(),
            cohortProperties = cohorts ?: emptyMap(),
            flagsByKey = flags,
            evaluationCache = evaluationCache,
        )
    }
}
