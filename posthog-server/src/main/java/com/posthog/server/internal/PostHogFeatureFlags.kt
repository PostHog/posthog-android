package com.posthog.server.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogOnFeatureFlags
import com.posthog.internal.FeatureFlag
import com.posthog.internal.PostHogApi
import com.posthog.internal.PostHogFeatureFlagsInterface

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

    // Local evaluation state
    @Volatile
    private var featureFlags: List<FlagDefinition>? = null

    @Volatile
    private var flagDefinitions: Map<String, FlagDefinition>? = null

    @Volatile
    private var cohorts: Map<String, CohortDefinition>? = null

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
            )
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
        )?.metadata?.payload
            ?: defaultValue
    }

    private fun fetchRemoteFlags(
        distinctId: String,
        groups: Map<String, String>?,
        personProperties: Map<String, String>?,
        groupProperties: Map<String, String>?,
    ): Map<String, FeatureFlag>? {
        val cacheKey =
            FeatureFlagCacheKey(
                distinctId = distinctId,
                groups = groups,
                personProperties = personProperties,
                groupProperties = groupProperties,
            )

        val cachedFlags = cache.get(cacheKey)
        if (cachedFlags != null) {
            return cachedFlags
        }

        return try {
            val response = api.flags(distinctId, null, groups, personProperties, groupProperties)
            val flags = response?.flags
            cache.put(cacheKey, flags)
            flags
        } catch (e: Throwable) {
            config.logger.log("Loading remote feature flags failed: $e")
            null
        }
    }

    private fun resolveFeatureFlag(
        key: String,
        distinctId: String,
        groups: Map<String, String>?,
        personProperties: Map<String, String>?,
        groupProperties: Map<String, String>?,
    ): FeatureFlag? {
        val cacheKey =
            FeatureFlagCacheKey(
                distinctId = distinctId,
                groups = groups,
                personProperties = personProperties,
                groupProperties = groupProperties,
            )

        val cachedFlags = cache.get(cacheKey)
        if (cachedFlags != null) {
            config.logger.log("Feature flags cache hit for distinctId: $distinctId")
            val flag = cachedFlags[key]
            if (flag != null) {
                return flag
            }
        }

        if (localEvaluation) {
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
                    return flag
                } catch (e: InconclusiveMatchException) {
                    config.logger.log("Local evaluation inconclusive for flag '$key': ${e.message}")
                    // Fall through to remote evaluation
                } catch (e: Throwable) {
                    config.logger.log("Local evaluation failed for flag '$key': ${e.message}")
                    // Fall through to remote evaluation
                }
            }
        }

        // Local evaluation not available or failed - fall back to API
        // Fetch and cache all flags, then return the specific one
        config.logger.log("Feature flag cache miss for distinctId: $distinctId, calling API")
        return fetchRemoteFlags(distinctId, groups, personProperties, groupProperties)?.get(key)
    }

    override fun getFeatureFlags(
        distinctId: String?,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): Map<String, FeatureFlag>? {
        if (distinctId == null) {
            config.logger.log("getFeatureFlags called but no distinctId available for API call")
            return null
        }

        val cacheKey =
            FeatureFlagCacheKey(
                distinctId = distinctId,
                groups = groups,
                personProperties = personProperties,
                groupProperties = groupProperties,
            )

        // Check cache first
        val cachedFlags = cache.get(cacheKey)
        if (cachedFlags != null) {
            config.logger.log("Feature flags cache hit for distinctId: $distinctId")
            return cachedFlags
        }

        // Try local evaluation if enabled and flags are loaded
        val currentFlagDefinitions = flagDefinitions ?: emptyMap()
        if (localEvaluation && currentFlagDefinitions.isNotEmpty()) {
            try {
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
                        // Skip this flag, it will be fetched from API as a fallback below
                    }
                }

                if (localFlags.isNotEmpty()) {
                    config.logger.log("Local evaluation successful for ${localFlags.size} flags")
                    // Don't cache locally evaluated flags, as they depend on properties
                    return localFlags
                }
            } catch (e: Throwable) {
                config.logger.log("Local evaluation failed: ${e.message}")
                // Fall through to API call
            }
        }

        // Cache miss or local evaluation failed - fall back to API
        config.logger.log("Feature flags cache miss for distinctId: $distinctId, calling API")
        return fetchRemoteFlags(distinctId, groups, personProperties, groupProperties)
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

        try {
            config.logger.log("Loading feature flags for local evaluation")
            val response = api.localEvaluation(personalApiKey)

            if (response != null) {
                // Parse flag definitions
                val flags =
                    response.flags?.mapNotNull { flagMap ->
                        try {
                            parseFlagDefinition(flagMap)
                        } catch (e: Exception) {
                            config.logger.log("Failed to parse flag definition: ${e.message}")
                            null
                        }
                    }

                featureFlags = flags
                flagDefinitions = flags?.associateBy { it.key }
                cohorts = response.cohorts?.mapValues { parseCohortDefinition(it.value) }
                groupTypeMapping = response.groupTypeMapping

                config.logger.log("Loaded ${flags?.size ?: 0} feature flags for local evaluation")

                if (!definitionsLoaded) {
                    definitionsLoaded = true
                    try {
                        onFeatureFlags?.loaded()
                    } catch (e: Throwable) {
                        config.logger.log("Error in onFeatureFlags callback: ${e.message}")
                    }
                }
            }
        } catch (e: Throwable) {
            config.logger.log("Failed to load feature flags for local evaluation: ${e.message}")
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
     * Parse a flag definition from JSON map
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseFlagDefinition(flagMap: Map<String, Any?>): FlagDefinition {
        return FlagDefinition(
            id = (flagMap["id"] as? Number)?.toInt() ?: 0,
            name = flagMap["name"] as? String ?: "",
            key = flagMap["key"] as? String ?: "",
            active = flagMap["active"] as? Boolean ?: false,
            filters = parseFilters(flagMap["filters"] as? Map<String, Any?>),
            version = (flagMap["version"] as? Number)?.toInt() ?: 0,
        )
    }

    /**
     * Parse filters from JSON map
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseFilters(filtersMap: Map<String, Any?>?): FlagFilters {
        val groups =
            (filtersMap?.get("groups") as? List<Map<String, Any?>>)?.map { parseConditionGroup(it) }
        val multivariate = parseMultiVariate(filtersMap?.get("multivariate") as? Map<String, Any?>)
        val payloads = filtersMap?.get("payloads") as? Map<String, Any?>

        return FlagFilters(
            groups = groups,
            multivariate = multivariate,
            payloads = payloads,
        )
    }

    /**
     * Parse a condition group from JSON map
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseConditionGroup(groupMap: Map<String, Any?>): FlagConditionGroup {
        val properties =
            (groupMap["properties"] as? List<Map<String, Any?>>)?.map { parseProperty(it) }
        val rolloutPercentage = (groupMap["rollout_percentage"] as? Number)?.toInt()
        val variant = groupMap["variant"] as? String

        return FlagConditionGroup(
            properties = properties,
            rolloutPercentage = rolloutPercentage,
            variant = variant,
        )
    }

    /**
     * Parse a property from JSON map
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseProperty(propMap: Map<String, Any?>): FlagProperty {
        return FlagProperty(
            key = propMap["key"] as? String ?: "",
            propertyValue = propMap["value"],
            propertyOperator = PropertyOperator.fromStringOrNull(propMap["operator"] as? String),
            type = PropertyType.fromStringOrNull(propMap["type"] as? String),
            negation = propMap["negation"] as? Boolean,
            dependencyChain = propMap["dependency_chain"] as? List<String>,
        )
    }

    /**
     * Parse multivariate config from JSON map
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseMultiVariate(multivariateMap: Map<String, Any?>?): MultiVariateConfig? {
        if (multivariateMap == null) return null

        val variants =
            (multivariateMap["variants"] as? List<Map<String, Any?>>)?.map { variantMap ->
                VariantDefinition(
                    key = variantMap["key"] as? String ?: "",
                    rolloutPercentage =
                        (variantMap["rollout_percentage"] as? Number)?.toDouble()
                            ?: 0.0,
                )
            }

        return MultiVariateConfig(variants = variants)
    }

    /**
     * Parse cohort definition from JSON map
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseCohortDefinition(cohortMap: Map<String, Any?>): CohortDefinition {
        return CohortDefinition(
            type = cohortMap["type"] as? String,
            values = cohortMap["values"] as? List<Any>,
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
     * Compute a flag locally using the evaluation engine
     */
    @Suppress("UNUSED_PARAMETER")
    private fun computeFlagLocally(
        key: String,
        distinctId: String,
        personProperties: Map<String, Any?>,
        groups: Map<String, String>?,
        groupProperties: Map<String, String>?,
    ): Any? {
        val flags = this.flagDefinitions ?: return null
        val flag = flags[key] ?: return null

        if (!flag.active) {
            return false
        }

        // Merge person and group properties for evaluation
        val allProperties = personProperties.toMutableMap()
        // Add group properties if available
        groupProperties?.forEach { (k, v) -> allProperties[k] = v }

        val evaluationCache = mutableMapOf<String, Any?>()
        return evaluator.matchFeatureFlagProperties(
            flag = flag,
            distinctId = distinctId,
            properties = allProperties,
            cohortProperties = cohorts ?: emptyMap(),
            flagsByKey = flags,
            evaluationCache = evaluationCache,
        )
    }
}
