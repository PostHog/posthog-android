package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogOnFeatureFlags
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

    fun loadFeatureFlags(
        distinctId: String,
        anonymousId: String,
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
                val response = api.decide(distinctId, anonymousId, groups)

                response?.let {
                    synchronized(featureFlagsLock) {
                        if (response.errorsWhileComputingFlags) {
                            // if not all flags were computed, we upsert flags instead of replacing them
                            this.featureFlags =
                                (this.featureFlags ?: mapOf()) + (response.featureFlags ?: mapOf())

                            val normalizedPayloads = normalizePayloads(response.featureFlagPayloads)

                            this.featureFlagPayloads = (this.featureFlagPayloads ?: mapOf()) + normalizedPayloads
                        } else {
                            this.featureFlags = response.featureFlags

                            val normalizedPayloads = normalizePayloads(response.featureFlagPayloads)
                            this.featureFlagPayloads = normalizedPayloads
                        }
                    }
                    isFeatureFlagsLoaded = true
                } ?: run {
                    isFeatureFlagsLoaded = false
                }
            } catch (e: Throwable) {
                isFeatureFlagsLoaded = false
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

    fun isFeatureEnabled(key: String, defaultValue: Boolean): Boolean {
        if (!isFeatureFlagsLoaded) {
            return defaultValue
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

    private fun readFeatureFlag(key: String, defaultValue: Any?, flags: Map<String, Any?>?): Any? {
        if (!isFeatureFlagsLoaded) {
            return defaultValue
        }
        val value: Any?

        synchronized(featureFlagsLock) {
            value = flags?.get(key)
        }

        return value ?: defaultValue
    }

    fun getFeatureFlag(key: String, defaultValue: Any?): Any? {
        return readFeatureFlag(key, defaultValue, featureFlags)
    }

    fun getFeatureFlagPayload(key: String, defaultValue: Any?): Any? {
        return readFeatureFlag(key, defaultValue, featureFlagPayloads)
    }

    fun getFeatureFlags(): Map<String, Any>? {
        val flags: Map<String, Any>?
        synchronized(featureFlagsLock) {
            flags = featureFlags?.toMap()
        }
        return flags
    }

    fun clear() {
        synchronized(featureFlagsLock) {
            featureFlags = null
            featureFlagPayloads = null
        }
    }
}
