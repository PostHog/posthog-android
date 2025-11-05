package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogInternal
import com.posthog.PostHogOnFeatureFlags
import com.posthog.internal.PostHogPreferences.Companion.FEATURE_FLAGS
import com.posthog.internal.PostHogPreferences.Companion.FEATURE_FLAGS_PAYLOAD
import com.posthog.internal.PostHogPreferences.Companion.FEATURE_FLAG_REQUEST_ID
import com.posthog.internal.PostHogPreferences.Companion.FLAGS
import com.posthog.internal.PostHogPreferences.Companion.SESSION_REPLAY
import com.posthog.internal.PostHogPreferences.Companion.SURVEYS
import com.posthog.surveys.Survey
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The class responsible for calling and caching the feature flags
 * @property config the Config
 * @property api the API
 * @property executor the Executor
 * @property getDefaultPersonProperties the lambda to get default person properties
 */
@PostHogInternal
public class PostHogRemoteConfig(
    private val config: PostHogConfig,
    private val api: PostHogApi,
    private val executor: ExecutorService,
    private val getDefaultPersonProperties: () -> Map<String, Any> = { emptyMap() },
) : PostHogFeatureFlagsInterface {
    private var isLoadingFeatureFlags = AtomicBoolean(false)
    private var isLoadingRemoteConfig = AtomicBoolean(false)

    private val featureFlagsLock = Any()
    private val remoteConfigLock = Any()

    private val personPropertiesForFlagsLock = Any()
    private var personPropertiesForFlags: MutableMap<String, Any> = mutableMapOf()

    private val groupPropertiesForFlagsLock = Any()
    private var groupPropertiesForFlags: MutableMap<String, MutableMap<String, Any>> = mutableMapOf()

    private var featureFlags: Map<String, Any>? = null
    private var featureFlagPayloads: Map<String, Any?>? = null

    // Flags v2 flags. These will later supersede featureFlags and featureFlagPayloads
    // But for now, we need to support both for back compatibility
    private var flags: Map<String, Any>? = null
    private var requestId: String? = null

    private var surveys: List<Survey>? = null

    @Volatile
    private var isFeatureFlagsLoaded = false

    @Volatile
    private var sessionReplayFlagActive = false

    @Volatile
    private var hasSurveys = false

    /**
     * Optional callback invoked after remote config finishes loading and surveys have been processed.
     * Use this to notify listeners that cached surveys may have changed.
     */
    @Volatile
    public var onRemoteConfigLoaded: (() -> Unit)? = null

    init {
        preloadSessionReplayFlag()
        preloadSurveys()
        loadCachedPropertiesForFlags()
    }

    private fun isRecordingActive(
        featureFlags: Map<String, Any>,
        sessionRecording: Map<String, Any>,
    ): Boolean {
        var recordingActive = true

        // Check for boolean flags
        val linkedFlag = sessionRecording["linkedFlag"]
        if (linkedFlag is String) {
            val value = featureFlags[linkedFlag]
            recordingActive =
                when (value) {
                    is Boolean -> {
                        value
                    }
                    is String -> {
                        // if its a multi-variant flag linked to "any"
                        true
                    }
                    else -> {
                        // disable recording if the flag does not exist/quota limited
                        false
                    }
                }
        } else if (linkedFlag is Map<*, *>) {
            // Check for specific flag variant
            val flag = linkedFlag["flag"] as? String
            val variant = linkedFlag["variant"] as? String
            if (flag != null && variant != null) {
                val value = featureFlags[flag] as? String
                recordingActive = value == variant
            } else {
                // disable recording if the flag does not exist/quota limited
                recordingActive = false
            }
        }
        // check for multi flag variant (any)
        // val linkedFlag = sessionRecording["linkedFlag"] as? String,
        //    featureFlags[linkedFlag] != nil
        // is also a valid check but since we cannot check the value of the flag,
        // we consider session replay is active

        return recordingActive
    }

    private fun runOnFeatureFlagsCallbacks(
        internalOnFeatureFlags: PostHogOnFeatureFlags?,
        onFeatureFlags: PostHogOnFeatureFlags?,
    ) {
        // if we don't load the feature flags (because there are none), we need to call the callback
        // because the app might be waiting for it.
        try {
            internalOnFeatureFlags?.loaded()
            onFeatureFlags?.loaded()
        } catch (e: Throwable) {
            config.logger.log("Executing the feature flags callback failed: $e")
        }
    }

    public fun loadRemoteConfig(
        distinctId: String,
        anonymousId: String?,
        groups: Map<String, String>?,
        internalOnFeatureFlags: PostHogOnFeatureFlags? = null,
        onFeatureFlags: PostHogOnFeatureFlags? = null,
    ) {
        executor.executeSafely {
            if (config.networkStatus?.isConnected() == false) {
                config.logger.log("Network isn't connected.")
                runOnFeatureFlagsCallbacks(
                    internalOnFeatureFlags = internalOnFeatureFlags,
                    onFeatureFlags = onFeatureFlags,
                )
                return@executeSafely
            }

            if (isLoadingRemoteConfig.getAndSet(true)) {
                config.logger.log("Remote Config is being loaded already.")
                return@executeSafely
            }

            try {
                val response = api.remoteConfig()

                var shouldNotifyRemoteConfigLoaded = false

                response?.let {
                    synchronized(remoteConfigLock) {
                        processSessionRecordingConfig(it.sessionRecording)
                        processSurveys(it.surveys)

                        val hasFlags = it.hasFeatureFlags ?: false

                        if (hasFlags) {
                            if (config.preloadFeatureFlags) {
                                if (distinctId.isNotBlank()) {
                                    // do not process session replay from flags API
                                    // since its already cached via the remote config API
                                    executeFeatureFlags(
                                        distinctId,
                                        anonymousId,
                                        groups,
                                        internalOnFeatureFlags = internalOnFeatureFlags,
                                        onFeatureFlags = onFeatureFlags,
                                    )
                                } else {
                                    config.logger.log("Feature flags not loaded, distinctId is invalid: $distinctId")
                                    runOnFeatureFlagsCallbacks(
                                        internalOnFeatureFlags = internalOnFeatureFlags,
                                        onFeatureFlags = onFeatureFlags,
                                    )
                                }
                            }
                        } else {
                            // clear cache since there are no active flags on the server side
                            synchronized(featureFlagsLock) {
                                // we didn't call the API but we should there are no active flags
                                // because remote config API returned hasFeatureFlags=false
                                isFeatureFlagsLoaded = true
                                clearFlags()
                            }

                            runOnFeatureFlagsCallbacks(
                                internalOnFeatureFlags = internalOnFeatureFlags,
                                onFeatureFlags = onFeatureFlags,
                            )
                        }

                        // mark to notify outside the lock
                        shouldNotifyRemoteConfigLoaded = true
                    }
                } ?: run {
                    runOnFeatureFlagsCallbacks(
                        internalOnFeatureFlags = internalOnFeatureFlags,
                        onFeatureFlags = onFeatureFlags,
                    )
                }

                if (shouldNotifyRemoteConfigLoaded) {
                    try {
                        onRemoteConfigLoaded?.invoke()
                    } catch (e: Throwable) {
                        config.logger.log("Executing onRemoteConfigLoaded callback failed: $e")
                    }
                }
            } catch (e: Throwable) {
                runOnFeatureFlagsCallbacks(
                    internalOnFeatureFlags = internalOnFeatureFlags,
                    onFeatureFlags = onFeatureFlags,
                )
                config.logger.log("Loading remote config failed: $e")
            } finally {
                isLoadingRemoteConfig.set(false)
            }
        }
    }

    private fun clearSurveys() {
        hasSurveys = false
        config.cachePreferences?.remove(SURVEYS)
        surveys = null
    }

    private fun clearSessionRecording() {
        sessionReplayFlagActive = false
        config.cachePreferences?.remove(SESSION_REPLAY)
    }

    private fun processSurveys(surveys: Any?) {
        if (!config.surveys) {
            // if surveys is disabled, we clear the surveys
            clearSurveys()
            return
        }

        when (surveys) {
            is Boolean -> {
                // If surveys is a boolean, it's always false
                clearSurveys()
            }

            is Collection<*> -> {
                val surveysData = surveys as? List<*>

                if (surveysData.isNullOrEmpty()) {
                    clearSurveys()
                    return
                }

                try {
                    val deserialized = config.serializer.deserializeList<Survey>(surveysData)
                    if (deserialized.isNullOrEmpty()) {
                        clearSurveys()
                        return
                    }

                    this.surveys = deserialized
                    hasSurveys = true
                    config.cachePreferences?.setValue(SURVEYS, surveysData)
                } catch (e: Throwable) {
                    clearSurveys()
                    config.logger.log("Error deserializing surveys: $e")
                }
            }

            else -> {
                clearSurveys()
            }
        }
    }

    private fun processSessionRecordingConfig(sessionRecording: Any?) {
        when (sessionRecording) {
            is Boolean -> {
                // if sessionRecording is a Boolean, its always disabled
                // so we don't enable sessionReplayFlagActive here
                sessionReplayFlagActive = sessionRecording

                if (!sessionRecording) {
                    config.cachePreferences?.remove(SESSION_REPLAY)
                } else {
                    // do nothing
                }
            }

            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                (sessionRecording as? Map<String, Any>)?.let {
                    // keeps the value from config.sessionReplay since having sessionRecording
                    // means its enabled on the project settings, but its only enabled
                    // when local config.sessionReplay is also enabled
                    config.snapshotEndpoint = it["endpoint"] as? String
                        ?: config.snapshotEndpoint

                    sessionReplayFlagActive = isRecordingActive(this.featureFlags ?: mapOf(), it)
                    config.cachePreferences?.setValue(SESSION_REPLAY, it)

                    // TODO:
                    // consoleLogRecordingEnabled -> Boolean or null
                    // networkPayloadCapture -> Boolean or null, can also be networkPayloadCapture={recordBody=true, recordHeaders=true}
                    // sampleRate, etc
                }
            }
            else -> {
                // do nothing
            }
        }
    }

    private fun executeFeatureFlags(
        distinctId: String,
        anonymousId: String?,
        groups: Map<String, String>?,
        internalOnFeatureFlags: PostHogOnFeatureFlags?,
        onFeatureFlags: PostHogOnFeatureFlags?,
    ) {
        if (config.networkStatus?.isConnected() == false) {
            config.logger.log("Network isn't connected.")
            runOnFeatureFlagsCallbacks(
                internalOnFeatureFlags = internalOnFeatureFlags,
                onFeatureFlags = onFeatureFlags,
            )
            return
        }

        if (isLoadingFeatureFlags.getAndSet(true)) {
            config.logger.log("Feature flags are being loaded already.")
            return
        }

        try {
            val response =
                api.flags(
                    distinctId,
                    anonymousId = anonymousId,
                    groups = groups,
                    personProperties = getPersonPropertiesForFlags(),
                    groupProperties = getGroupPropertiesForFlags(),
                )

            response?.let {
                synchronized(featureFlagsLock) {
                    if (it.quotaLimited?.contains("feature_flags") == true) {
                        config.logger.log(
                            """Feature flags are quota limited, clearing existing flags.
                                    Learn more about billing limits at https://posthog.com/docs/billing/limits-alerts""",
                        )
                        clearFlags()
                        return@let
                    }

                    val normalizedResponse = normalizeFlagsResponse(it)

                    if (normalizedResponse.errorsWhileComputingFlags) {
                        // if not all flags were computed, we upsert flags instead of replacing them
                        this.flags = (this.flags ?: mapOf()) + (normalizedResponse.flags ?: mapOf())

                        this.featureFlags =
                            (this.featureFlags ?: mapOf()) + (normalizedResponse.featureFlags ?: mapOf())

                        val normalizedPayloads = normalizePayloads(normalizedResponse.featureFlagPayloads)

                        this.featureFlagPayloads =
                            (this.featureFlagPayloads ?: mapOf()) + normalizedPayloads
                    } else {
                        this.flags = normalizedResponse.flags
                        this.featureFlags = normalizedResponse.featureFlags
                        val normalizedPayloads = normalizePayloads(normalizedResponse.featureFlagPayloads)
                        this.featureFlagPayloads = normalizedPayloads
                    }

                    // since flags might have changed, we need to check if session recording is active again
                    processSessionRecordingConfig(it.sessionRecording)
                }
                config.cachePreferences?.let { preferences ->
                    val flags = this.flags ?: mapOf()
                    preferences.setValue(FLAGS, flags)

                    val featureFlags = this.featureFlags ?: mapOf()
                    preferences.setValue(FEATURE_FLAGS, featureFlags)

                    val payloads = this.featureFlagPayloads ?: mapOf()
                    preferences.setValue(FEATURE_FLAGS_PAYLOAD, payloads)
                }
                isFeatureFlagsLoaded = true
            } ?: run {
                isFeatureFlagsLoaded = false
            }
        } catch (e: Throwable) {
            config.logger.log("Loading feature flags failed: $e")
        } finally {
            runOnFeatureFlagsCallbacks(
                internalOnFeatureFlags = internalOnFeatureFlags,
                onFeatureFlags = onFeatureFlags,
            )
            isLoadingFeatureFlags.set(false)
        }
    }

    public fun loadFeatureFlags(
        distinctId: String,
        anonymousId: String?,
        groups: Map<String, String>?,
        internalOnFeatureFlags: PostHogOnFeatureFlags? = null,
        onFeatureFlags: PostHogOnFeatureFlags? = null,
    ) {
        executor.executeSafely {
            executeFeatureFlags(
                distinctId,
                anonymousId,
                groups,
                internalOnFeatureFlags = internalOnFeatureFlags,
                onFeatureFlags = onFeatureFlags,
            )
        }
    }

    private fun preloadSurveys() {
        synchronized(remoteConfigLock) {
            if (!config.surveys) {
                clearSurveys()
                return
            }

            try {
                @Suppress("UNCHECKED_CAST")
                val surveysData = config.cachePreferences?.getValue(SURVEYS) as? List<Map<String, Any>>?
                if (surveysData.isNullOrEmpty()) {
                    clearSurveys()
                    return
                }

                val surveys = config.serializer.deserializeList<Survey>(surveysData)
                if (surveys.isNullOrEmpty()) {
                    clearSurveys()
                    return
                }

                this.surveys = surveys
                hasSurveys = true
            } catch (e: Throwable) {
                config.logger.log("Error deserializing surveys: $e")
                clearSurveys()
            }
        }
    }

    private fun preloadSessionReplayFlag() {
        synchronized(featureFlagsLock) {
            config.cachePreferences?.let { preferences ->
                @Suppress("UNCHECKED_CAST")
                val sessionRecording = preferences.getValue(SESSION_REPLAY) as? Map<String, Any>

                @Suppress("UNCHECKED_CAST")
                val flags = preferences.getValue(FEATURE_FLAGS) as? Map<String, Any>

                if (sessionRecording != null) {
                    sessionReplayFlagActive = isRecordingActive(flags ?: mapOf(), sessionRecording)

                    config.snapshotEndpoint = sessionRecording["endpoint"] as? String
                        ?: config.snapshotEndpoint
                }
            }
        }
    }

    private fun loadFeatureFlagsFromCache() {
        config.cachePreferences?.let { preferences ->
            @Suppress("UNCHECKED_CAST")
            val flags =
                preferences.getValue(
                    FLAGS,
                    mapOf<String, Any>(),
                ) as? Map<String, Any> ?: mapOf()

            @Suppress("UNCHECKED_CAST")
            val featureFlags =
                preferences.getValue(
                    FEATURE_FLAGS,
                    mapOf<String, Any>(),
                ) as? Map<String, Any> ?: mapOf()

            @Suppress("UNCHECKED_CAST")
            val payloads =
                preferences.getValue(
                    FEATURE_FLAGS_PAYLOAD,
                    mapOf<String, Any?>(),
                ) as? Map<String, Any?> ?: mapOf()

            val cachedRequestId = preferences.getValue(FEATURE_FLAG_REQUEST_ID) as? String

            synchronized(featureFlagsLock) {
                this.flags = flags
                this.featureFlags = featureFlags
                this.featureFlagPayloads = payloads
                this.requestId = cachedRequestId
                isFeatureFlagsLoaded = true
            }
        }
    }

    private fun normalizePayloads(featureFlagPayloads: Map<String, Any?>?): Map<String, Any?> {
        val parsedPayloads = (featureFlagPayloads ?: mapOf()).toMutableMap()

        for (item in parsedPayloads) {
            val value = item.value

            try {
                // only try to parse if its a String, since the JSON values are stringified
                if (value is String) {
                    // try to deserialize as Any?
                    config.serializer.deserializeString(value)?.let {
                        parsedPayloads[item.key] = it
                    }
                }
            } catch (ignored: Throwable) {
                // if it fails, we keep the original value
            }
        }
        return parsedPayloads
    }

    private fun normalizeFlagsResponse(flagsResponse: PostHogFlagsResponse): PostHogFlagsResponse {
        val flags = flagsResponse.flags
        if (flags != null) {
            // This is a v4 response. This means that `featureFlags` and `featureFlagPayloads`
            // are not populated. We need to populate them with the values from the flags property.
            val newResponse =
                flagsResponse.copy(
                    featureFlags = flags.mapValues { (_, value) -> value.variant ?: value.enabled },
                    featureFlagPayloads = flags.mapValues { (_, value) -> value.metadata.payload },
                )
            synchronized(featureFlagsLock) {
                // Store the requestId in the cache.
                this.requestId = newResponse.requestId
                this.requestId?.let { requestId ->
                    config.cachePreferences?.setValue(FEATURE_FLAG_REQUEST_ID, requestId as Any)
                }
            }
            return newResponse
        }
        return flagsResponse
    }

    private fun loadFeatureFlagsFromCacheIfNeeded() {
        if (!isFeatureFlagsLoaded) {
            loadFeatureFlagsFromCache()
        }
    }

    private fun readFeatureFlag(
        key: String,
        defaultValue: Any?,
        flags: Map<String, Any?>?,
    ): Any? {
        val value: Any?

        synchronized(featureFlagsLock) {
            value = flags?.get(key)
        }

        return value ?: defaultValue
    }

    override fun getFeatureFlag(
        key: String,
        defaultValue: Any?,
        distinctId: String?,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): Any? {
        // since we pass featureFlags as a value, we need to load it from cache if needed
        loadFeatureFlagsFromCacheIfNeeded()

        return readFeatureFlag(key, defaultValue, featureFlags)
    }

    override fun getFeatureFlagPayload(
        key: String,
        defaultValue: Any?,
        distinctId: String?,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): Any? {
        // since we pass featureFlags as a value, we need to load it from cache if needed
        loadFeatureFlagsFromCacheIfNeeded()

        return readFeatureFlag(key, defaultValue, featureFlagPayloads)
    }

    override fun getFeatureFlags(
        distinctId: String?,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): Map<String, Any>? {
        val flags: Map<String, Any>?
        synchronized(featureFlagsLock) {
            flags = featureFlags?.toMap()
        }
        return flags
    }

    public fun isSessionReplayFlagActive(): Boolean = sessionReplayFlagActive

    public fun getRequestId(): String? {
        loadFeatureFlagsFromCacheIfNeeded()
        synchronized(featureFlagsLock) {
            return requestId
        }
    }

    public fun getFlagDetails(key: String): FeatureFlag? {
        loadFeatureFlagsFromCacheIfNeeded()

        synchronized(featureFlagsLock) {
            return flags?.get(key) as? FeatureFlag
        }
    }

    public fun getSurveys(): List<Survey>? {
        synchronized(remoteConfigLock) {
            return surveys
        }
    }

    private fun clearFlags() {
        // call this method after synchronized(featureFlagsLock)
        this.featureFlags = null
        this.featureFlagPayloads = null
        this.flags = null
        this.requestId = null

        config.cachePreferences?.let { preferences ->
            preferences.remove(FLAGS)
            preferences.remove(FEATURE_FLAGS)
            preferences.remove(FEATURE_FLAGS_PAYLOAD)
            preferences.remove(FEATURE_FLAG_REQUEST_ID)
        }
    }

    public fun setPersonPropertiesForFlags(userProperties: Map<String, Any>) {
        synchronized(personPropertiesForFlagsLock) {
            personPropertiesForFlags.putAll(userProperties)
            config.cachePreferences?.setValue(
                PostHogPreferences.PERSON_PROPERTIES_FOR_FLAGS,
                personPropertiesForFlags,
            )
        }
    }

    public fun resetPersonPropertiesForFlags() {
        synchronized(personPropertiesForFlagsLock) {
            personPropertiesForFlags.clear()
            config.cachePreferences?.remove(PostHogPreferences.PERSON_PROPERTIES_FOR_FLAGS)
        }
    }

    public fun setGroupPropertiesForFlags(
        type: String,
        groupProperties: Map<String, Any>,
    ) {
        synchronized(groupPropertiesForFlagsLock) {
            val existing = groupPropertiesForFlags.getOrPut(type) { mutableMapOf() }
            existing.putAll(groupProperties)
            config.cachePreferences?.setValue(
                PostHogPreferences.GROUP_PROPERTIES_FOR_FLAGS,
                groupPropertiesForFlags,
            )
        }
    }

    public fun resetGroupPropertiesForFlags(type: String? = null) {
        synchronized(groupPropertiesForFlagsLock) {
            if (type != null) {
                groupPropertiesForFlags.remove(type)
                config.cachePreferences?.setValue(
                    PostHogPreferences.GROUP_PROPERTIES_FOR_FLAGS,
                    groupPropertiesForFlags,
                )
            } else {
                groupPropertiesForFlags.clear()
                config.cachePreferences?.remove(PostHogPreferences.GROUP_PROPERTIES_FOR_FLAGS)
            }
        }
    }

    private fun getPersonPropertiesForFlags(): Map<String, Any> {
        synchronized(personPropertiesForFlagsLock) {
            val properties = mutableMapOf<String, Any>()

            // Always include fresh default properties if enabled
            if (config.setDefaultPersonProperties) {
                val defaultProperties = getDefaultPersonProperties()
                properties.putAll(defaultProperties)
            }

            // User-set properties override default properties
            properties.putAll(personPropertiesForFlags)

            return properties
        }
    }

    private fun getGroupPropertiesForFlags(): Map<String, Map<String, Any>> {
        synchronized(groupPropertiesForFlagsLock) {
            return groupPropertiesForFlags.toMap()
        }
    }

    private fun loadCachedPropertiesForFlags() {
        synchronized(personPropertiesForFlagsLock) {
            @Suppress("UNCHECKED_CAST")
            val cachedPersonProperties =
                config.cachePreferences?.getValue(
                    PostHogPreferences.PERSON_PROPERTIES_FOR_FLAGS,
                ) as? Map<String, Any>

            cachedPersonProperties?.let {
                personPropertiesForFlags.putAll(it)
            }
        }

        synchronized(groupPropertiesForFlagsLock) {
            @Suppress("UNCHECKED_CAST")
            val cachedGroupProperties =
                config.cachePreferences?.getValue(
                    PostHogPreferences.GROUP_PROPERTIES_FOR_FLAGS,
                ) as? Map<String, Map<String, Any>>

            cachedGroupProperties?.let {
                it.forEach { (key, cachedValue) ->
                    groupPropertiesForFlags[key] = cachedValue.toMutableMap()
                }
            }
        }
    }

    override fun clear() {
        synchronized(featureFlagsLock) {
            sessionReplayFlagActive = false
            isFeatureFlagsLoaded = false

            clearFlags()
        }

        synchronized(remoteConfigLock) {
            clearSurveys()
        }

        // Clear person and group properties for flags
        resetPersonPropertiesForFlags()
        resetGroupPropertiesForFlags()

        config.cachePreferences?.remove(SESSION_REPLAY)
    }
}
