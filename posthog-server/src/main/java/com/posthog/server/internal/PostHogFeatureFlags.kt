package com.posthog.server.internal

import com.posthog.PostHogConfig
import com.posthog.internal.FeatureFlag
import com.posthog.internal.PostHogApi
import com.posthog.internal.PostHogFeatureFlagsInterface
import com.posthog.server.NullaryCallback


internal class PostHogFeatureFlags(
    private val config: PostHogConfig,
    private val api: PostHogApi,
    private val cacheMaxAgeMs: Int,
    private val cacheMaxSize: Int,
    private val enableLocalEvaluation: Boolean = false,
    private val personalApiKey: String? = null,
    private val pollIntervalSeconds: Int = 30,
    private val onLocalEvaluationReady: NullaryCallback? = null,
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
    private var flagsByKey: Map<String, FlagDefinition>? = null

    @Volatile
    private var cohorts: Map<String, CohortDefinition>? = null

    @Volatile
    private var groupTypeMapping: Map<String, String>? = null

    private val evaluator: FlagEvaluator = FlagEvaluator(config)

    private var poller: LocalEvaluationPoller? = null

    private var definitionsLoaded = false

    init {
        startPoller()
    }

    override fun getFeatureFlag(
        key: String,
        defaultValue: Any?,
        distinctId: String?,
        groups: Map<String, String>?,
        personProperties: Map<String, String>?,
        groupProperties: Map<String, String>?,
    ): Any? {
        val flag =
            getFeatureFlags(
                distinctId,
                groups,
                personProperties,
                groupProperties,
            )?.get(key)
        return flag?.variant ?: flag?.enabled ?: defaultValue
    }

    override fun getFeatureFlagPayload(
        key: String,
        defaultValue: Any?,
        distinctId: String?,
        groups: Map<String, String>?,
        personProperties: Map<String, String>?,
        groupProperties: Map<String, String>?,
    ): Any? {
        return getFeatureFlags(
            distinctId,
            groups,
            personProperties,
            groupProperties,
        )?.get(key)?.metadata?.payload
            ?: defaultValue
    }

    private fun buildCacheFromRemote(
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

        // Check cache first
        val cachedFlags = cache.get(cacheKey)
        if (cachedFlags != null) {
            config.logger.log("Feature flags cache hit for distinctId: $distinctId")
            return cachedFlags
        }

        // Cache miss or local evaluation failed - fall back to API
        config.logger.log("Feature flags cache miss for distinctId: $distinctId, calling API")
        return try {
            val response = api.flags(distinctId, null, groups, personProperties, groupProperties)
            val flags = response?.flags
            cache.put(cacheKey, flags)
            flags
        } catch (e: Throwable) {
            config.logger.log("Loading feature flags failed: $e")
            null
        }
    }

    // TODO: Don't resolve ALL local flags for the current user when requesting just a single flag
    //.      We should only need to compute the one single flag. UNLESS local evaluation is not available,
    //.      fetch and cache all the flags for the distinct id
    override fun getFeatureFlags(
        distinctId: String?,
        groups: Map<String, String>?,
        personProperties: Map<String, String>?,
        groupProperties: Map<String, String>?,
    ): Map<String, FeatureFlag>? {
        if (distinctId == null) {
            config.logger.log("getFeatureFlags called but no distinctId available for API call")
            return null
        }

        // Create cache key from parameters
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
        if (enableLocalEvaluation && flagsByKey != null) {
            try {
                config.logger.log("Attempting local evaluation for distinctId: $distinctId")
                val localFlags = mutableMapOf<String, FeatureFlag>()
                val props = (personProperties ?: emptyMap()).toMutableMap()

                // Evaluate all flags locally
                for ((key, flagDef) in flagsByKey!!) {
                    try {
                        val result =
                            computeFlagLocally(
                                key = key,
                                distinctId = distinctId,
                                personProperties = props,
                                groups = groups,
                                groupProperties = groupProperties,
                            )

                        // Convert result to FeatureFlag
                        // Get payload from flag definition if available
                        val payload =
                            flagDef.filters.payloads?.get(result?.toString() ?: "true")?.toString()

                        when (result) {
                            is String -> {
                                // Variant
                                localFlags[key] =
                                    FeatureFlag(
                                        key = key,
                                        enabled = true,
                                        variant = result,
                                        metadata = com.posthog.internal.FeatureFlagMetadata(
                                            id = flagDef.id,
                                            payload = payload,
                                            version = 1,
                                        ),
                                        reason = null,
                                    )
                            }

                            is Boolean -> {
                                localFlags[key] =
                                    FeatureFlag(
                                        key = key,
                                        enabled = result,
                                        variant = null,
                                        metadata = com.posthog.internal.FeatureFlagMetadata(
                                            id = flagDef.id,
                                            payload = payload,
                                            version = 1,
                                        ),
                                        reason = null,
                                    )
                            }

                            else -> {
                                localFlags[key] =
                                    FeatureFlag(
                                        key = key,
                                        enabled = false,
                                        variant = null,
                                        metadata = com.posthog.internal.FeatureFlagMetadata(
                                            id = flagDef.id,
                                            payload = null,
                                            version = 1,
                                        ),
                                        reason = null,
                                    )
                            }
                        }
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
        return try {
            val response = api.flags(distinctId, null, groups, personProperties, groupProperties)
            val flags = response?.flags
            cache.put(cacheKey, flags)
            flags
        } catch (e: Throwable) {
            config.logger.log("Loading feature flags failed: $e")
            null
        }
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
        if (!enableLocalEvaluation || personalApiKey == null) {
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
                flagsByKey = flags?.associateBy { it.key }
                cohorts = response.cohorts?.mapValues { parseCohortDefinition(it.value) }
                groupTypeMapping = response.groupTypeMapping

                config.logger.log("Loaded ${flags?.size ?: 0} feature flags for local evaluation")

                if (!definitionsLoaded) {
                    definitionsLoaded = true
                    try {
                        onLocalEvaluationReady?.invoke()
                    } catch (e: Throwable) {
                        config.logger.log("Error in onLocalEvaluationReady callback: ${e.message}")
                    }
                }
            }
        } catch (e: Throwable) {
            config.logger.log("Failed to load feature flags for local evaluation: ${e.message}")
        }
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
            value = propMap["value"],
            operator = propMap["operator"] as? String,
            type = propMap["type"] as? String,
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
                    rolloutPercentage = (variantMap["rollout_percentage"] as? Number)?.toDouble()
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
        if (!enableLocalEvaluation) {
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
                        execute = { loadFeatureFlagDefinitions() }
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
        val flags = this.flagsByKey ?: return null
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
