package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogOnFeatureFlags
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class PostHogFeatureFlags(
    private val config: PostHogConfig,
    private val api: PostHogApi,
    private val executor: ExecutorService = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("PostHogSendCachedEventsThread")),
) {

    private var isLoadingFeatureFlags = AtomicBoolean(false)

    private val featureFlagsLock = Any()

    private var featureFlags: Map<String, Any>? = null
    private var featureFlagPayloads: Map<String, Any>? = null

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
                        if (!response.errorsWhileComputingFlags) {
                            this.featureFlags = (this.featureFlags ?: mapOf()) + (response.featureFlags ?: mapOf())
                            this.featureFlagPayloads = (this.featureFlagPayloads ?: mapOf()) + (response.featureFlagPayloads ?: mapOf())
                        } else {
                            this.featureFlags = response.featureFlags
                            this.featureFlagPayloads = response.featureFlagPayloads
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
                    onFeatureFlags?.notify(getFeatureFlags())
                } catch (e: Throwable) {
                    config.logger.log("Executing the feature flags callback failed: $e")
                } finally {
                    isLoadingFeatureFlags.set(false)
                }
            }
        }
    }

    fun isFeatureEnabled(key: String, defaultValue: Boolean): Boolean {
        if (!isFeatureFlagsLoaded) {
            return defaultValue
        }
        val value: Any?

        synchronized(featureFlagsLock) {
            value = featureFlags?.get(key)
        }

        return if (value is Boolean) {
            value
        } else {
            defaultValue
        }
    }

    private fun readFeatureFlag(key: String, defaultValue: Any?, flags: Map<String, Any>?): Any? {
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
