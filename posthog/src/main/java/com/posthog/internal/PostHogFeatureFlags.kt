package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogOnFeatureFlags
import com.posthog.internal.PostHogPreferences.Companion.FEATURE_FLAGS
import com.posthog.internal.PostHogPreferences.Companion.FEATURE_FLAGS_PAYLOAD
import com.posthog.internal.PostHogPreferences.Companion.SESSION_REPLAY
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The class responsible for calling and caching the feature flags
 * @property config the Config
 * @property api the API
 * @property executor the Executor
 */
internal class PostHogFeatureFlags(
    private val config: PostHogConfig,
    private val api: PostHogApi,
    private val executor: ExecutorService,
) {
    private var isLoadingFeatureFlags = AtomicBoolean(false)

    private val featureFlagsLock = Any()

    private var featureFlags: Map<String, Any>? = null
    private var featureFlagPayloads: Map<String, Any?>? = null

    @Volatile
    private var isFeatureFlagsLoaded = false

    @Volatile
    private var sessionReplayFlagActive = false

    init {
        preloadSessionReplayFlag()
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
            if (value is Boolean) {
                recordingActive = value
            }
        } else if (linkedFlag is Map<*, *>) {
            // Check for specific flag variant
            val flag = linkedFlag["flag"] as? String
            val variant = linkedFlag["variant"] as? String
            if (flag != null && variant != null) {
                val value = featureFlags[flag] as? String
                recordingActive = value == variant
            }
        }
        // check for multi flag variant (any)
        // val linkedFlag = sessionRecording["linkedFlag"] as? String,
        //    featureFlags[linkedFlag] != nil
        // is also a valid check but since we cannot check the value of the flag,
        // we consider session recording is active

        return recordingActive
    }

    fun loadFeatureFlags(
        distinctId: String,
        anonymousId: String?,
        groups: Map<String, String>?,
        onFeatureFlags: PostHogOnFeatureFlags?,
    ) {
        executor.executeSafely {
            if (config.networkStatus?.isConnected() == false) {
                config.logger.log("Network isn't connected.")
                return@executeSafely
            }

            if (isLoadingFeatureFlags.getAndSet(true)) {
                config.logger.log("Feature flags are being loaded already.")
                return@executeSafely
            }

            try {
                val response = api.decide(distinctId, anonymousId = anonymousId, groups)

                response?.let {
                    synchronized(featureFlagsLock) {
                        if (response.quotaLimited?.contains("feature_flags") == true) {
                            config.logger.log("Feature flags are quota limited, clearing existing flags")
                            this.featureFlags = null
                            this.featureFlagPayloads = null
                            config.cachePreferences?.let { preferences ->
                                preferences.remove(FEATURE_FLAGS)
                                preferences.remove(FEATURE_FLAGS_PAYLOAD)
                            }
                            return@let
                        }

                        if (response.errorsWhileComputingFlags) {
                            // if not all flags were computed, we upsert flags instead of replacing them
                            this.featureFlags =
                                (this.featureFlags ?: mapOf()) + (response.featureFlags ?: mapOf())

                            val normalizedPayloads = normalizePayloads(response.featureFlagPayloads)

                            this.featureFlagPayloads =
                                (this.featureFlagPayloads ?: mapOf()) + normalizedPayloads
                        } else {
                            this.featureFlags = response.featureFlags

                            val normalizedPayloads = normalizePayloads(response.featureFlagPayloads)
                            this.featureFlagPayloads = normalizedPayloads
                        }

                        when (val sessionRecording = response.sessionRecording) {
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
                    config.cachePreferences?.let { preferences ->
                        val flags = this.featureFlags ?: mapOf()
                        preferences.setValue(FEATURE_FLAGS, flags)

                        val payloads = this.featureFlagPayloads ?: mapOf()
                        preferences.setValue(FEATURE_FLAGS_PAYLOAD, payloads)
                    }
                    isFeatureFlagsLoaded = true
                }
            } catch (e: Throwable) {
                config.logger.log("Loading feature flags failed: $e")
            } finally {
                try {
                    onFeatureFlags?.loaded()
                } catch (e: Throwable) {
                    config.logger.log("Executing the feature flags callback failed: $e")
                } finally {
                    isLoadingFeatureFlags.set(false)
                }
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
                    FEATURE_FLAGS,
                    mapOf<String, Any>(),
                ) as? Map<String, Any> ?: mapOf()

            @Suppress("UNCHECKED_CAST")
            val payloads =
                preferences.getValue(
                    FEATURE_FLAGS_PAYLOAD,
                    mapOf<String, Any?>(),
                ) as? Map<String, Any?> ?: mapOf()

            synchronized(featureFlagsLock) {
                this.featureFlags = flags
                this.featureFlagPayloads = payloads

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

    fun isFeatureEnabled(
        key: String,
        defaultValue: Boolean,
    ): Boolean {
        if (!isFeatureFlagsLoaded) {
            loadFeatureFlagsFromCache()
        }
        val value: Any?

        synchronized(featureFlagsLock) {
            value = featureFlags?.get(key)
        }

        return if (value != null) {
            if (value is Boolean) {
                value
            } else {
                // if its multivariant flag, its enabled by default
                true
            }
        } else {
            defaultValue
        }
    }

    private fun readFeatureFlag(
        key: String,
        defaultValue: Any?,
        flags: Map<String, Any?>?,
    ): Any? {
        if (!isFeatureFlagsLoaded) {
            loadFeatureFlagsFromCache()
        }
        val value: Any?

        synchronized(featureFlagsLock) {
            value = flags?.get(key)
        }

        return value ?: defaultValue
    }

    fun getFeatureFlag(
        key: String,
        defaultValue: Any?,
    ): Any? {
        return readFeatureFlag(key, defaultValue, featureFlags)
    }

    fun getFeatureFlagPayload(
        key: String,
        defaultValue: Any?,
    ): Any? {
        return readFeatureFlag(key, defaultValue, featureFlagPayloads)
    }

    fun getFeatureFlags(): Map<String, Any>? {
        val flags: Map<String, Any>?
        synchronized(featureFlagsLock) {
            flags = featureFlags?.toMap()
        }
        return flags
    }

    fun isSessionReplayFlagActive(): Boolean = sessionReplayFlagActive

    fun clear() {
        synchronized(featureFlagsLock) {
            featureFlags = null
            featureFlagPayloads = null
            sessionReplayFlagActive = false

            config.cachePreferences?.let { preferences ->
                preferences.remove(FEATURE_FLAGS)
                preferences.remove(FEATURE_FLAGS_PAYLOAD)
                preferences.remove(SESSION_REPLAY)
            }
        }
    }
}
