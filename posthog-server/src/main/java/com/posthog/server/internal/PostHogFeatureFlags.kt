package com.posthog.server.internal

import com.posthog.FeatureFlagResult
import com.posthog.PostHogConfig
import com.posthog.PostHogOnFeatureFlags
import com.posthog.internal.FeatureFlag
import com.posthog.internal.FlagDefinition
import com.posthog.internal.LocalEvaluationResponse
import com.posthog.internal.PostHogApi
import com.posthog.internal.PostHogApiError
import com.posthog.internal.PostHogFeatureFlagsInterface
import com.posthog.internal.PostHogFlagsResponse
import com.posthog.internal.PropertyGroup
import com.posthog.server.PostHogFlagDefinitionCacheProvider
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class PostHogFeatureFlags(
    private val config: PostHogConfig,
    private val api: PostHogApi,
    private val cacheMaxAgeMs: Int,
    private val cacheMaxSize: Int,
    private val localEvaluation: Boolean = false,
    private val personalApiKey: String? = null,
    private val pollIntervalSeconds: Int = 30,
    private val onFeatureFlags: PostHogOnFeatureFlags? = null,
    private val pollerEnabled: Boolean = true,
    private val flagDefinitionCacheProvider: PostHogFlagDefinitionCacheProvider? = null,
    private val missingFlagKeysMaxSize: Int = DEFAULT_MISSING_FLAG_KEYS_MAX_SIZE,
    private val missingFlagProbeWaitTimeoutMs: Long = MISSING_FLAG_PROBE_WAIT_TIMEOUT_MS,
) : PostHogFeatureFlagsInterface {
    private val cache =
        PostHogFeatureFlagCache(
            maxSize = cacheMaxSize,
            maxAgeMs = cacheMaxAgeMs,
        )

    private val missingFlagKeysLock = Object()
    private val knownMissingFlagKeys = boundedFlagEvidenceMap<Unit>()
    private val knownRemoteFlagKeys = boundedFlagEvidenceMap<Long>()
    private val inFlightMissingFlagProbes = mutableMapOf<String, MissingFlagProbe>()
    private var missingFlagKeysGeneration: Long = 0
    private var remoteFlagEvidenceSequence: Long = 0

    @Volatile
    private var featureFlags: List<FlagDefinition>? = null

    @Volatile
    private var flagDefinitions: Map<String, FlagDefinition>? = null

    @Volatile
    private var cohorts: Map<String, PropertyGroup>? = null

    @Volatile
    private var groupTypeMapping: Map<String, String>? = null

    private val evaluator: FlagEvaluator = FlagEvaluator(config)

    @Volatile
    private var poller: LocalEvaluationPoller? = null

    @Volatile
    private var definitionsLoaded = false

    @Volatile
    private var definitionsLoadedAt: Long? = null

    @Volatile
    private var isLoading = false

    private val loadLock = Object()

    /**
     * ETag for conditional requests to reduce bandwidth when polling for feature flags.
     * When flags haven't changed, the server returns 304 Not Modified instead of the full payload.
     */
    @Volatile
    private var etag: String? = null

    init {
        try {
            startPoller()
        } catch (e: Throwable) {
            config.logger.log("Poller failed to init: $e")
            stopPoller()
        }

        if (!localEvaluation) {
            try {
                onFeatureFlags?.loaded()
            } catch (e: Throwable) {
                config.logger.log("Error in onFeatureFlags callback: ${e.message}")
            }
        }
    }

    override fun getFeatureFlagResult(
        key: String,
        distinctId: String?,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): FeatureFlagResult? {
        if (distinctId == null) {
            return null
        }
        val flag =
            resolveFeatureFlag(
                key,
                distinctId,
                groups,
                personProperties,
                groupProperties,
            ) ?: return null

        return FeatureFlagResult(key, flag.enabled, flag.variant, flag.metadata.payload)
    }

    internal fun getFeatureFlag(
        key: String,
        defaultValue: Any? = null,
        distinctId: String? = null,
        groups: Map<String, String>? = null,
        personProperties: Map<String, Any?>? = null,
        groupProperties: Map<String, Map<String, Any?>>? = null,
    ): Any? {
        val result =
            getFeatureFlagResult(
                key,
                distinctId,
                groups,
                personProperties,
                groupProperties,
            )
        return when {
            result == null -> defaultValue
            result.variant != null -> result.variant
            else -> result.enabled
        }
    }

    internal fun getFeatureFlagPayload(
        key: String,
        defaultValue: Any? = null,
        distinctId: String? = null,
        groups: Map<String, String>? = null,
        personProperties: Map<String, Any?>? = null,
        groupProperties: Map<String, Map<String, Any?>>? = null,
    ): Any? {
        return getFeatureFlagResult(
            key,
            distinctId,
            groups,
            personProperties,
            groupProperties,
        )?.payload ?: defaultValue
    }

    private fun resolveFeatureFlag(
        key: String,
        distinctId: String,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): FeatureFlag? {
        val cachedFlags =
            getFeatureFlagsFromCache(distinctId, groups, personProperties, groupProperties)
        if (cachedFlags != null) {
            config.logger.log("Feature flags cache hit for distinctId: $distinctId")
            val flag = cachedFlags[key]
            if (flag != null) {
                return flag
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
                    val props = localPersonProperties(distinctId, personProperties)

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
        return getFeatureFlagsFromRemote(
            distinctId,
            groups,
            personProperties,
            groupProperties,
        )?.get(key)
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

    /**
     * The result of one local evaluation pass. [flags] holds every flag that resolved, whether or
     * not [needsRemote] is set, so callers can fill only the gaps from `/flags`.
     */
    private data class LocalEvaluationOutcome(
        val flags: Map<String, FeatureFlag>,
        val needsRemote: Boolean,
        val missingDefinitionKeys: Set<String>,
    )

    private class MissingFlagProbe {
        private val done = CountDownLatch(1)

        fun complete() = done.countDown()

        fun await(timeoutMs: Long): Boolean =
            try {
                done.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
    }

    private data class MissingFlagProbePlan(
        val generation: Long,
        val currentlyMissing: Set<String>,
        val knownRemote: Map<String, Long>,
        val owned: Set<String>,
        val waiting: List<MissingFlagProbe>,
        val probe: MissingFlagProbe?,
    )

    /**
     * Evaluate flags against the definitions held in memory, recording failures per flag.
     *
     * @param flagKeys when non-null, only these keys are evaluated. This scopes the loop, never
     *   [flagDefinitions] itself: [computeFlagLocally] resolves flag dependencies through the full
     *   map, so narrowing the field would make every dependent flag inconclusive.
     * @return null when local evaluation is unavailable: disabled, or definitions never loaded.
     */
    private fun evaluateFlagsLocally(
        distinctId: String,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
        flagKeys: List<String>?,
    ): LocalEvaluationOutcome? {
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
        val props = localPersonProperties(distinctId, personProperties)
        val requestedKeys = flagKeys?.toHashSet()
        var needsRemote = false

        for ((key, flagDef) in currentFlagDefinitions) {
            if (requestedKeys != null && key !in requestedKeys) {
                continue
            }

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
                needsRemote = true
            } catch (e: Exception) {
                config.logger.log("Local evaluation failed for flag '$key': ${e.message}")
                needsRemote = true
            }
        }

        val missingDefinitionKeys =
            flagKeys
                ?.distinct()
                ?.filterNot { currentFlagDefinitions.containsKey(it) }
                ?.toSet()
                .orEmpty()
        if (missingDefinitionKeys.isNotEmpty()) {
            config.logger.log(
                "No local definition for requested flag(s) ${missingDefinitionKeys.joinToString(", ")} - " +
                    "eligible for remote fallback",
            )
        }

        config.logger.log("Local evaluation resolved ${localFlags.size} flags, needsRemote=$needsRemote")
        return LocalEvaluationOutcome(localFlags, needsRemote, missingDefinitionKeys)
    }

    /**
     * All-or-nothing local evaluation for the deprecated [getFeatureFlags] path: a single
     * unresolved flag discards the batch so the caller falls back to `/flags`.
     */
    private fun getFeatureFlagsFromLocalEvaluation(
        distinctId: String,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): Map<String, FeatureFlag>? {
        val outcome =
            evaluateFlagsLocally(
                distinctId,
                groups,
                personProperties,
                groupProperties,
                flagKeys = null,
            ) ?: return null

        return if (outcome.needsRemote) null else outcome.flags
    }

    private fun localPersonProperties(
        distinctId: String,
        personProperties: Map<String, Any?>?,
    ): MutableMap<String, Any?> {
        val props = (personProperties ?: EMPTY_PROPERTIES).toMutableMap()
        props.putIfAbsent("distinct_id", distinctId)
        return props
    }

    private fun getFeatureFlagsFromRemote(
        distinctId: String,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
        flagKeys: List<String>? = null,
        disableGeoip: Boolean = false,
        bypassCache: Boolean = false,
        onResponse: ((PostHogFlagsResponse) -> Unit)? = null,
    ): Map<String, FeatureFlag>? {
        val cacheKey =
            FeatureFlagCacheKey(
                distinctId = distinctId,
                groups = groups,
                personProperties = personProperties,
                groupProperties = groupProperties,
                flagKeys = flagKeys,
                disableGeoip = disableGeoip,
            )

        if (!bypassCache) {
            val cachedFlags = cache.get(cacheKey)
            if (cachedFlags != null) {
                return cachedFlags
            }
        }

        return try {
            val responseGeneration = synchronized(missingFlagKeysLock) { missingFlagKeysGeneration }
            val response =
                api.flags(
                    distinctId,
                    anonymousId = null,
                    deviceId = null,
                    groups = groups,
                    personProperties = personProperties,
                    groupProperties = groupProperties,
                    flagKeys = flagKeys,
                    disableGeoip = disableGeoip,
                )
            val flags = response?.flags
            cache.put(
                cacheKey,
                flags,
                response?.requestId,
                response?.evaluatedAt,
                computeResponseError(response),
            )
            if (response != null) {
                reconcileReturnedFlagEvidence(responseGeneration, response)
                onResponse?.invoke(response)
            }
            flags
        } catch (e: SocketTimeoutException) {
            config.logger.log("Loading remote feature flags timed out: $e")
            cache.put(cacheKey, null, error = FeatureFlagError.TIMEOUT)
            null
        } catch (e: ConnectException) {
            config.logger.log("Loading remote feature flags connection failed: $e")
            cache.put(cacheKey, null, error = FeatureFlagError.CONNECTION_ERROR)
            null
        } catch (e: UnknownHostException) {
            config.logger.log("Loading remote feature flags DNS lookup failed: $e")
            cache.put(cacheKey, null, error = FeatureFlagError.CONNECTION_ERROR)
            null
        } catch (e: PostHogApiError) {
            config.logger.log("Loading remote feature flags API error: $e")
            cache.put(cacheKey, null, error = FeatureFlagError.apiError(e.statusCode))
            null
        } catch (e: Throwable) {
            config.logger.log("Loading remote feature flags failed: $e")
            cache.put(cacheKey, null, error = FeatureFlagError.UNKNOWN_ERROR)
            null
        }
    }

    /**
     * Compute error string from a successful API response.
     * Returns null if there are no errors in the response.
     */
    private fun computeResponseError(response: PostHogFlagsResponse?): String? {
        val errors = mutableListOf<String>()
        if (response?.errorsWhileComputingFlags == true) {
            errors.add(FeatureFlagError.ERRORS_WHILE_COMPUTING)
        }
        if (response?.quotaLimited?.contains("feature_flags") == true) {
            errors.add(FeatureFlagError.QUOTA_LIMITED)
        }
        return if (errors.isNotEmpty()) errors.joinToString(",") else null
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

        val cached = getFeatureFlagsFromCache(distinctId, groups, personProperties, groupProperties)
        if (cached != null) {
            return cached
        }

        // If no cached flags, try local evaluation
        val localFlags =
            getFeatureFlagsFromLocalEvaluation(
                distinctId,
                groups,
                personProperties,
                groupProperties,
            )
        if (localFlags != null) {
            return localFlags
        }

        // Finally, fall back to remote fetch
        return getFeatureFlagsFromRemote(distinctId, groups, personProperties, groupProperties)
    }

    override fun clear() {
        cache.clear()
        clearKnownMissingFlagKeys()
        etag = null
        config.logger.log("Feature flags cache cleared")
    }

    override fun shutDown() {
        stopPoller()
        shutdownFlagDefinitionCacheProvider()
    }

    /**
     * Load feature flag definitions from a shared cache or from the API for local evaluation.
     * Uses ETag for conditional requests to reduce bandwidth when flags haven't changed.
     */
    public fun loadFeatureFlagDefinitions() {
        if (!localEvaluation) {
            return
        }
        if (personalApiKey == null) {
            logMissingPersonalApiKey()
            return
        }

        var wasWaitingForLoad = false

        synchronized(loadLock) {
            while (isLoading) {
                wasWaitingForLoad = true
                try {
                    loadLock.wait()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    config.logger.log("Interrupted while waiting for flag definitions to load")
                    return
                }
            }

            if (wasWaitingForLoad && flagDefinitions != null) {
                config.logger.log("Definitions loaded by another thread, skipping duplicate request")
                return
            }

            isLoading = true
        }

        var shouldFetch = true

        try {
            shouldFetch = shouldFetchFlagDefinitions()

            if (!shouldFetch) {
                val loadedFromCache = loadFeatureFlagDefinitionsFromCache()
                if (loadedFromCache) {
                    return
                }

                if (definitionsLoaded) {
                    config.logger.log("Flag definition cache empty, keeping existing definitions")
                    return
                }

                config.logger.log("Flag definition cache empty before initial load, falling back to API")
            }

            config.logger.log("Loading feature flags for local evaluation")
            val response = api.localEvaluation(personalApiKey, etag)

            // If 304 Not Modified, keep using cached data (update ETag if server sent a new one)
            if (!response.wasModified) {
                etag = response.etag ?: etag
                clearKnownMissingFlagKeys()
                config.logger.log("Feature flags not modified, using cached definitions")
                return
            }

            // On failure (no result), preserve existing ETag for retry
            val apiResponse = response.result
            if (apiResponse == null) {
                return
            }

            // Success: update ETag (or clear if server stopped sending one)
            etag = response.etag

            val cacheData = buildFlagDefinitionCacheData(apiResponse)
            applyFlagDefinitions(
                flags = apiResponse.flags,
                groupTypeMapping = apiResponse.groupTypeMapping,
                cohorts = apiResponse.cohorts,
            )

            config.logger.log("Loaded ${apiResponse.flags?.size ?: 0} feature flags for local evaluation")

            if (shouldFetch && cacheData != null) {
                storeFlagDefinitionsInCache(cacheData)
            }

            notifyFeatureFlagsLoaded()
        } catch (e: PostHogApiError) {
            // Clear ETag on API errors (4xx/5xx) so next request starts fresh
            etag = null
            config.logger.log("Failed to load feature flags for local evaluation: ${e.message}")
        } catch (e: IOException) {
            // Preserve ETag on network errors - likely transient, retry with same ETag
            config.logger.log("Network error loading feature flags (will retry): ${e.message}")
        } catch (e: Throwable) {
            // Clear ETag on unexpected errors
            etag = null
            config.logger.log("Unexpected error loading feature flags: ${e.message}")
        } finally {
            synchronized(loadLock) {
                isLoading = false
                loadLock.notifyAll()
            }
        }
    }

    private fun shouldFetchFlagDefinitions(): Boolean {
        val provider = flagDefinitionCacheProvider ?: return true
        return awaitFlagDefinitionCacheProvider(
            errorDescription = "Error in flag definition cache provider shouldFetchFlagDefinitions",
        ) {
            provider.shouldFetchFlagDefinitions()
        } ?: true
    }

    private fun loadFeatureFlagDefinitionsFromCache(): Boolean {
        val provider = flagDefinitionCacheProvider ?: return false
        val cachedData =
            awaitFlagDefinitionCacheProvider(
                errorDescription = "Error loading feature flag definitions from cache provider",
            ) {
                provider.getFlagDefinitions()
            } ?: return false

        return try {
            val response = parseFlagDefinitionCacheData(cachedData)
            applyFlagDefinitions(
                flags = response.flags,
                groupTypeMapping = response.groupTypeMapping,
                cohorts = response.cohorts,
            )
            config.logger.log("Loaded ${response.flags?.size ?: 0} feature flags from flag definition cache")
            notifyFeatureFlagsLoaded()
            true
        } catch (e: Throwable) {
            config.logger.log("Error loading feature flag definitions from cache provider: ${e.message}")
            false
        }
    }

    private fun <T> awaitFlagDefinitionCacheProvider(
        errorDescription: String,
        call: () -> CompletionStage<T>,
    ): T? {
        val future =
            try {
                call().toCompletableFuture()
            } catch (e: Throwable) {
                config.logger.log("$errorDescription: ${e.message}")
                return null
            }

        return try {
            future.get(FLAG_DEFINITION_CACHE_PROVIDER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            config.logger.log("$errorDescription: interrupted")
            null
        } catch (e: TimeoutException) {
            future.cancel(true)
            config.logger.log("$errorDescription: timed out after ${FLAG_DEFINITION_CACHE_PROVIDER_TIMEOUT_MS}ms")
            null
        } catch (e: ExecutionException) {
            config.logger.log("$errorDescription: ${e.cause?.message ?: e.message}")
            null
        } catch (e: Throwable) {
            config.logger.log("$errorDescription: ${e.message}")
            null
        }
    }

    private fun parseFlagDefinitionCacheData(data: Map<String, Any?>): LocalEvaluationResponse {
        val writer = StringWriter()
        config.serializer.serialize(data, writer)
        return config.serializer.deserialize(StringReader(writer.toString()))
    }

    private fun buildFlagDefinitionCacheData(response: LocalEvaluationResponse): Map<String, Any?>? {
        return try {
            val cacheData: Map<String, Any?> =
                mapOf(
                    "flags" to (response.flags ?: emptyList<FlagDefinition>()),
                    "group_type_mapping" to (response.groupTypeMapping ?: emptyMap<String, String>()),
                    "cohorts" to (response.cohorts ?: emptyMap<String, PropertyGroup>()),
                )
            val writer = StringWriter()
            config.serializer.serialize(cacheData, writer)
            config.serializer.deserialize<Map<String, Any?>>(StringReader(writer.toString()))
        } catch (e: Throwable) {
            config.logger.log("Error preparing flag definitions for cache provider: ${e.message}")
            null
        }
    }

    private fun storeFlagDefinitionsInCache(data: Map<String, Any?>) {
        val provider = flagDefinitionCacheProvider ?: return
        awaitFlagDefinitionCacheProvider(
            errorDescription = "Error storing feature flag definitions in cache provider",
        ) {
            provider.onFlagDefinitionsReceived(data)
        }
    }

    private fun applyFlagDefinitions(
        flags: List<FlagDefinition>?,
        groupTypeMapping: Map<String, String>?,
        cohorts: Map<String, PropertyGroup>?,
    ) {
        val invalidated =
            synchronized(missingFlagKeysLock) {
                synchronized(loadLock) {
                    featureFlags = flags
                    flagDefinitions = flags?.associateBy { it.key }
                    this.cohorts = cohorts
                    this.groupTypeMapping = groupTypeMapping
                    definitionsLoaded = true
                    definitionsLoadedAt = System.currentTimeMillis()
                }
                invalidateMissingFlagStateLocked()
            }
        invalidated.forEach { it.complete() }
    }

    private fun clearKnownMissingFlagKeys() {
        val invalidated = synchronized(missingFlagKeysLock) { invalidateMissingFlagStateLocked() }
        invalidated.forEach { it.complete() }
    }

    private fun invalidateMissingFlagStateLocked(): List<MissingFlagProbe> {
        knownMissingFlagKeys.clear()
        knownRemoteFlagKeys.clear()
        missingFlagKeysGeneration++
        return inFlightMissingFlagProbes.values.distinct().also { inFlightMissingFlagProbes.clear() }
    }

    private fun notifyFeatureFlagsLoaded() {
        try {
            onFeatureFlags?.loaded()
        } catch (e: Throwable) {
            config.logger.log("Error in onFeatureFlags callback: ${e.message}")
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
                    hasExperiment = flagDef.hasExperiment,
                ),
            reason =
                com.posthog.internal.EvaluationReason(
                    code = LOCAL_EVALUATION_REASON_CODE,
                    description = LOCAL_EVALUATION_REASON_DESCRIPTION,
                    condition_index = null,
                ),
        )
    }

    /**
     * Start the poller for local evaluation if enabled
     */
    private fun startPoller() {
        if (!localEvaluation || !pollerEnabled) {
            return
        }

        if (personalApiKey == null) {
            logMissingPersonalApiKey()
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

    private fun shutdownFlagDefinitionCacheProvider() {
        val provider = flagDefinitionCacheProvider ?: return
        awaitFlagDefinitionCacheProvider(
            errorDescription = "Error shutting down flag definition cache provider",
        ) {
            provider.shutdown()
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
            properties = evaluationProperties ?: EMPTY_PROPERTIES,
            cohortProperties = cohorts ?: EMPTY_COHORT_PROPERTIES,
            flagsByKey = flags,
            evaluationCache = evaluationCache,
        )
    }

    private fun localEvaluationEnabled(): Boolean {
        return localEvaluation && personalApiKey != null
    }

    private fun logMissingPersonalApiKey() {
        config.logger.log("Local evaluation requires a personal API key. This call will be ignored.")
    }

    /**
     * Get the requestId from the cache for the given distinctId and groups
     */
    override fun getRequestId(
        distinctId: String?,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): String? = getCacheEntry(distinctId, groups, personProperties, groupProperties)?.requestId

    /**
     * Get the evaluatedAt from the cache for the given distinctId and groups
     */
    override fun getEvaluatedAt(
        distinctId: String?,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): Long? = getCacheEntry(distinctId, groups, personProperties, groupProperties)?.evaluatedAt

    private fun getCacheEntry(
        distinctId: String?,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): FeatureFlagCacheEntry? {
        if (distinctId == null) {
            return null
        }
        val cacheKey =
            FeatureFlagCacheKey(
                distinctId = distinctId,
                groups = groups,
                personProperties = personProperties,
                groupProperties = groupProperties,
            )
        return cache.getEntry(cacheKey)
    }

    /**
     * Get feature flag error for the given flag key and user context.
     * Returns a comma-separated string of error types if any errors occurred during evaluation.
     *
     * Possible error values:
     * - "errors_while_computing_flags": Server returned errorsWhileComputingFlags=true
     * - "flag_missing": Requested flag not in API response
     * - "quota_limited": Rate/quota limit exceeded
     * - "timeout": Request timed out
     * - "connection_error": Network connectivity issue
     * - "api_error_XXX": API returned error with status code XXX
     * - "unknown_error": Unexpected error
     *
     * Multiple errors are joined with commas, e.g., "errors_while_computing_flags,flag_missing"
     */
    override fun getFeatureFlagDetails(
        key: String,
        distinctId: String?,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): FeatureFlag? {
        if (distinctId == null) {
            return null
        }
        val cacheKey =
            FeatureFlagCacheKey(
                distinctId = distinctId,
                groups = groups,
                personProperties = personProperties,
                groupProperties = groupProperties,
            )
        return cache.getEntry(cacheKey)?.flags?.get(key)
    }

    /**
     * Resolve every flag for the given identity in a single pass, returning the rich envelope used
     * by the [com.posthog.server.PostHogFeatureFlagEvaluations] snapshot.
     *
     * Local evaluation runs first and wins: whatever the definitions in memory resolve is kept.
     * Unresolved requested keys trigger `/flags`, which receives the caller's original [flagKeys]
     * scope, including keys that resolved locally.
     */
    internal fun evaluateFlags(
        distinctId: String,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
        flagKeys: List<String>?,
        onlyEvaluateLocally: Boolean,
        disableGeoip: Boolean,
    ): EvaluateFlagsResult {
        if (flagKeys?.isEmpty() == true) {
            return EMPTY_EVALUATE_FLAGS_RESULT
        }

        if (onlyEvaluateLocally && personalApiKey == null) {
            logMissingPersonalApiKey()
            return EMPTY_EVALUATE_FLAGS_RESULT
        }

        val cacheKey =
            FeatureFlagCacheKey(
                distinctId = distinctId,
                groups = groups,
                personProperties = personProperties,
                groupProperties = groupProperties,
                flagKeys = flagKeys,
                disableGeoip = disableGeoip,
            )
        // Without definitions there is nothing to evaluate locally, so an existing entry ends the
        // call. This keeps the cached-failure backoff, and caps the blocking `/local_evaluation`
        // attempt below: a personal API key that always fails never sets `definitionsLoaded`.
        if (flagDefinitions == null) {
            cache.getEntry(cacheKey)?.let { entry ->
                // Local-only mode uses the entry's existence, never its values.
                if (onlyEvaluateLocally) {
                    return EMPTY_EVALUATE_FLAGS_RESULT
                }
                val flags = entry.flags ?: EMPTY_FLAGS
                return EvaluateFlagsResult(
                    flags = flags,
                    locallyEvaluated = flags.mapValues { isLocallyEvaluated(it.value) },
                    requestId = entry.requestId,
                    evaluatedAt = entry.evaluatedAt,
                    definitionsLoadedAt = definitionsLoadedAt,
                    responseError = entry.error,
                )
            }
        }

        val local =
            evaluateFlagsLocally(
                distinctId,
                groups,
                personProperties,
                groupProperties,
                flagKeys,
            )

        val localFlags = local?.flags ?: EMPTY_FLAGS
        val missingDefinitionKeys = local?.missingDefinitionKeys.orEmpty()
        val hasMissingKeyToProbe =
            synchronized(missingFlagKeysLock) {
                missingDefinitionKeys.any {
                    knownMissingFlagKeys[it] == null || flagDefinitions?.containsKey(it) == true
                }
            }

        if (local != null && ((!local.needsRemote && !hasMissingKeyToProbe) || onlyEvaluateLocally)) {
            return EvaluateFlagsResult(
                flags = localFlags,
                locallyEvaluated = localFlags.mapValues { true },
                requestId = null,
                evaluatedAt = null,
                definitionsLoadedAt = definitionsLoadedAt,
                responseError = null,
            )
        }

        if (onlyEvaluateLocally) {
            return EMPTY_EVALUATE_FLAGS_RESULT
        }

        val (remoteFlags, entry) =
            if (missingDefinitionKeys.isNotEmpty()) {
                evaluateMissingFlagsRemotely(
                    cacheKey,
                    distinctId,
                    groups,
                    personProperties,
                    groupProperties,
                    flagKeys,
                    disableGeoip,
                    missingDefinitionKeys,
                    local?.needsRemote == true,
                )
            } else {
                var cached = cache.getEntry(cacheKey)
                val flags =
                    if (cached != null) {
                        cached.flags
                    } else {
                        getFeatureFlagsFromRemote(
                            distinctId,
                            groups,
                            personProperties,
                            groupProperties,
                            flagKeys,
                            disableGeoip,
                        ).also { cached = cache.getEntry(cacheKey) }
                    }
                flags to cached
            }

        // Forward the caller's original scope to `/flags`; locally resolved values win below.
        // Local wins: `/flags` fills the gaps, it never overwrites a key local evaluation resolved.
        // Same precedence as posthog-python, which skips remote keys already in
        // `locally_evaluated_keys`. Note a group flag evaluated without `groups` resolves locally to
        // `false`, and that now beats the server's answer — pass `groups` when gating on one.
        val merged = LinkedHashMap(remoteFlags ?: EMPTY_FLAGS).apply { putAll(localFlags) }
        return EvaluateFlagsResult(
            flags = merged,
            locallyEvaluated = merged.mapValues { it.key in localFlags },
            requestId = entry?.requestId,
            evaluatedAt = entry?.evaluatedAt,
            definitionsLoadedAt = definitionsLoadedAt,
            responseError = entry?.error,
        )
    }

    private fun evaluateMissingFlagsRemotely(
        cacheKey: FeatureFlagCacheKey,
        distinctId: String,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
        flagKeys: List<String>?,
        disableGeoip: Boolean,
        missingDefinitionKeys: Set<String>,
        localNeedsRemote: Boolean,
    ): Pair<Map<String, FeatureFlag>?, FeatureFlagCacheEntry?> {
        while (true) {
            val plan = planMissingFlagProbe(missingDefinitionKeys)

            if (plan.waiting.isNotEmpty()) {
                if (!plan.waiting.all { it.await(missingFlagProbeWaitTimeoutMs) }) return null to null
                continue
            }

            val refreshMadeKeysLocal = plan.currentlyMissing.size < missingDefinitionKeys.size
            val needsRemote =
                localNeedsRemote || refreshMadeKeysLocal || plan.knownRemote.isNotEmpty() || plan.owned.isNotEmpty()
            if (!needsRemote) return null to null

            var entry = cache.getEntry(cacheKey)
            val bypassCache = shouldBypassCacheFor(plan, refreshMadeKeysLocal, entry)
            if (bypassCache) entry = null
            var response: PostHogFlagsResponse? = null
            val flags =
                try {
                    if (entry != null) {
                        entry.flags
                    } else {
                        getFeatureFlagsFromRemote(
                            distinctId,
                            groups,
                            personProperties,
                            groupProperties,
                            flagKeys,
                            disableGeoip,
                            bypassCache = bypassCache,
                            onResponse = { response = it },
                        ).also { entry = cache.getEntry(cacheKey) }
                    }
                } finally {
                    completeMissingFlagProbe(plan, response)
                }
            return flags to entry
        }
    }

    private fun planMissingFlagProbe(missingDefinitionKeys: Set<String>): MissingFlagProbePlan =
        synchronized(missingFlagKeysLock) {
            val current = missingDefinitionKeys.filterNot { flagDefinitions?.containsKey(it) == true }.toSet()
            val remote = current.mapNotNull { key -> knownRemoteFlagKeys[key]?.let { key to it } }.toMap()
            val unknown =
                current.filterNotTo(mutableSetOf()) {
                    knownMissingFlagKeys[it] != null || knownRemoteFlagKeys[it] != null
                }
            val waiting = unknown.mapNotNull { inFlightMissingFlagProbes[it] }.distinct()
            val probe = if (unknown.isNotEmpty() && waiting.isEmpty()) MissingFlagProbe() else null
            if (probe != null) unknown.forEach { inFlightMissingFlagProbes[it] = probe }
            MissingFlagProbePlan(
                missingFlagKeysGeneration,
                current,
                remote,
                if (probe == null) emptySet() else unknown,
                waiting,
                probe,
            )
        }

    private fun shouldBypassCacheFor(
        plan: MissingFlagProbePlan,
        refreshMadeKeysLocal: Boolean,
        entry: FeatureFlagCacheEntry?,
    ): Boolean =
        plan.owned.isNotEmpty() ||
            refreshMadeKeysLocal ||
            plan.knownRemote.keys.any { entry?.flags?.containsKey(it) != true }

    private fun completeMissingFlagProbe(
        plan: MissingFlagProbePlan,
        response: PostHogFlagsResponse?,
    ) {
        val probe = plan.probe
        val returned = response?.flags.orEmpty().keys
        synchronized(missingFlagKeysLock) {
            if (missingFlagKeysGeneration == plan.generation) {
                // Positive evidence applies even when another unknown key owned the fallback.
                plan.currentlyMissing.filterTo(mutableSetOf()) { it in returned }.forEach {
                    retainKnownRemoteFlagKeyLocked(it)
                }
                if (response?.isCleanForSuppression() == true) {
                    for (key in plan.owned) {
                        if (key !in returned && key !in knownRemoteFlagKeys) {
                            retainKnownMissingFlagKeyLocked(key)
                        }
                    }
                    for ((key, evidenceSequence) in plan.knownRemote) {
                        // A delayed omission cannot replace positive evidence published after this probe began.
                        if (key !in returned && knownRemoteFlagKeys[key] == evidenceSequence) {
                            retainKnownMissingFlagKeyLocked(key)
                        }
                    }
                }
            }
            if (probe != null) {
                plan.owned.forEach {
                    if (inFlightMissingFlagProbes[it] === probe) inFlightMissingFlagProbes.remove(it)
                }
            }
        }
        probe?.complete()
    }

    private fun reconcileReturnedFlagEvidence(
        responseGeneration: Long,
        response: PostHogFlagsResponse,
    ) {
        val returned = response.flags.orEmpty().keys
        if (returned.isEmpty()) return
        synchronized(missingFlagKeysLock) {
            if (missingFlagKeysGeneration != responseGeneration) return
            returned.filterTo(mutableSetOf()) {
                it in knownMissingFlagKeys || it in knownRemoteFlagKeys
            }.forEach {
                retainKnownRemoteFlagKeyLocked(it)
            }
        }
    }

    private fun retainKnownMissingFlagKeyLocked(key: String) {
        knownRemoteFlagKeys.remove(key)
        knownMissingFlagKeys[key] = Unit
    }

    private fun retainKnownRemoteFlagKeyLocked(key: String) {
        knownMissingFlagKeys.remove(key)
        remoteFlagEvidenceSequence++
        knownRemoteFlagKeys[key] = remoteFlagEvidenceSequence
    }

    private fun <V> boundedFlagEvidenceMap(): LinkedHashMap<String, V> =
        object : LinkedHashMap<String, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean =
                size > missingFlagKeysMaxSize.coerceAtLeast(0)
        }

    private fun PostHogFlagsResponse.isCleanForSuppression(): Boolean =
        !errorsWhileComputingFlags &&
            quotaLimited?.contains("feature_flags") != true

    private fun isLocallyEvaluated(flag: FeatureFlag): Boolean {
        return flag.reason?.code == LOCAL_EVALUATION_REASON_CODE
    }

    override fun getFeatureFlagError(
        key: String,
        distinctId: String?,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): String? {
        if (distinctId == null) {
            return null
        }

        val cacheKey =
            FeatureFlagCacheKey(
                distinctId = distinctId,
                groups = groups,
                personProperties = personProperties,
                groupProperties = groupProperties,
            )

        val entry = cache.getEntry(cacheKey) ?: return FeatureFlagError.UNKNOWN_ERROR

        // If request failed entirely (flags is null), return the cached error
        if (entry.flags == null) {
            return entry.error ?: FeatureFlagError.UNKNOWN_ERROR
        }

        // Request succeeded - check for flag_missing (key-specific, computed at query time)
        val flagMissing = !entry.flags.containsKey(key)

        return when {
            entry.error == null && !flagMissing -> null
            entry.error == null -> FeatureFlagError.FLAG_MISSING
            !flagMissing -> entry.error
            else -> "${entry.error},${FeatureFlagError.FLAG_MISSING}"
        }
    }

    internal companion object {
        internal const val LOCAL_EVALUATION_REASON_CODE: String = "local_evaluation"
        internal const val LOCAL_EVALUATION_REASON_DESCRIPTION: String = "Evaluated locally"
        private const val FLAG_DEFINITION_CACHE_PROVIDER_TIMEOUT_MS: Long = 10_000
        private const val MISSING_FLAG_PROBE_WAIT_TIMEOUT_MS: Long = 10_000
        private const val DEFAULT_MISSING_FLAG_KEYS_MAX_SIZE: Int = 1_000

        private val EMPTY_PROPERTIES: Map<String, Any?> = emptyMap()
        private val EMPTY_COHORT_PROPERTIES: Map<String, PropertyGroup> = emptyMap()
        private val EMPTY_FLAGS: Map<String, FeatureFlag> = emptyMap()
        private val EMPTY_LOCALLY_EVALUATED: Map<String, Boolean> = emptyMap()
        private val EMPTY_EVALUATE_FLAGS_RESULT =
            EvaluateFlagsResult(
                flags = EMPTY_FLAGS,
                locallyEvaluated = EMPTY_LOCALLY_EVALUATED,
                requestId = null,
                evaluatedAt = null,
                definitionsLoadedAt = null,
                responseError = null,
            )
    }
}
