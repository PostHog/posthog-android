package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogOnFeatureFlags
import com.posthog.internal.PostHogPreferences.Companion.FEATURE_FLAGS
import com.posthog.internal.PostHogPreferences.Companion.FEATURE_FLAGS_PAYLOAD
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
) : PostHogFeatureFlagsInterface {
    private var isLoadingFeatureFlags = AtomicBoolean(false)

    private val featureFlagsLock = Any()

    private var featureFlags: Map<String, Any>? = null
    private var featureFlagPayloads: Map<String, Any?>? = null

    @Volatile
    private var isFeatureFlagsLoaded = false

    override fun loadFeatureFlags(
        distinctId: String,
        anonymousId: String?,
        groups: Map<String, Any>?,
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
                val response = api.decide(distinctId, anonymousId = anonymousId, groups = groups)

                response?.let {
                    synchronized(featureFlagsLock) {
                        if (response.errorsWhileComputingFlags) {
                            // if not all flags were computed, we upsert flags instead of replacing them
                            this.featureFlags =
                                (this.featureFlags ?: mapOf()) + (response.featureFlags ?: mapOf())

                            val normalizedPayloads = normalizePayloads(config.serializer, response.featureFlagPayloads) ?: mapOf()

                            this.featureFlagPayloads = (this.featureFlagPayloads ?: mapOf()) + normalizedPayloads
                        } else {
                            this.featureFlags = response.featureFlags

                            val normalizedPayloads = normalizePayloads(config.serializer, response.featureFlagPayloads)
                            this.featureFlagPayloads = normalizedPayloads
                        }

                        if (response.sessionRecording is Boolean) {
                            // its only enabled if both are enabled, likely not in this case
                            // because if sessionRecording is a Boolean, its always disabled
                            config.sessionReplay = response.sessionRecording && config.sessionReplay
                        } else if (response.sessionRecording is Map<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            (response.sessionRecording as? Map<String, Any?>).let { sessionRecording ->
                                // keeps the value from config.sessionReplay since having sessionRecording
                                // means its enabled on the project settings, but its only enabled
                                // when local config.sessionReplay is also enabled
                                config.snapshotEndpoint = sessionRecording?.get("endpoint") as? String ?: config.snapshotEndpoint

                                // TODO:
                                // consoleLogRecordingEnabled -> Boolean or null
                                // networkPayloadCapture -> Boolean or null
                                // sampleRate, etc
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

    override fun isFeatureEnabled(
        key: String,
        defaultValue: Boolean,
        distinctId: String,
        anonymousId: String?,
        groups: Map<String, Any>?,
    ): Boolean {
        if (!isFeatureFlagsLoaded) {
            loadFeatureFlagsFromCache()
        }
        val value: Any?

        synchronized(featureFlagsLock) {
            value = featureFlags?.get(key)
        }

        return normalizeBoolean(value, defaultValue)
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

    override fun getFeatureFlag(
        key: String,
        defaultValue: Any?,
        distinctId: String,
        anonymousId: String?,
        groups: Map<String, Any>?,
    ): Any? {
        return readFeatureFlag(key, defaultValue, featureFlags)
    }

    override fun getFeatureFlagPayload(
        key: String,
        defaultValue: Any?,
        distinctId: String,
        anonymousId: String?,
        groups: Map<String, Any>?,
    ): Any? {
        return readFeatureFlag(key, defaultValue, featureFlagPayloads)
    }

    override fun getFeatureFlags(
        distinctId: String,
        anonymousId: String?,
        groups: Map<String, Any>?,
    ): Map<String, Any>? {
        val flags: Map<String, Any>?
        synchronized(featureFlagsLock) {
            flags = featureFlags?.toMap()
        }
        return flags
    }

    override fun clear() {
        synchronized(featureFlagsLock) {
            featureFlags = null
            featureFlagPayloads = null

            config.cachePreferences?.let { preferences ->
                preferences.remove(FEATURE_FLAGS)
                preferences.remove(FEATURE_FLAGS_PAYLOAD)
            }
        }
    }
}
