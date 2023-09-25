package com.posthog.internal

import com.posthog.PostHogConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class PostHogFeatureFlags(private val config: PostHogConfig, private val api: PostHogApi) {
    // TODO: do we need the onFeatureFlags callback?
    // fix me, yes, maybe a sync method is better UX

    private val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("PostHogDecideThread"))

    private var isLoadingFeatureFlags = AtomicBoolean(false)

    private val featureFlagsLock = Any()

    private var featureFlags: Map<String, Any>? = null
    private var featureFlagPayloads: Map<String, Any>? = null

    @Volatile
    private var isFeatureFlagsLoaded = false

    fun loadFeatureFlags(properties: Map<String, Any>) {
        executor.execute {
            if (config.networkStatus?.isConnected() != true) {
                config.logger.log("Network isn't connected.")
                return@execute
            }

            if (isLoadingFeatureFlags.getAndSet(true)) {
                config.logger.log("Feature flags are being loaded already.")
                return@execute
            }

            try {
                val result = api.decide(properties)

                val errorsWhileComputingFlags = result?.get("errorsWhileComputingFlags") as? Boolean ?: false

                val featureFlags = result?.get("featureFlags") as? Map<String, Any> ?: mapOf()
                val featureFlagPayloads = result?.get("featureFlagPayloads") as? Map<String, Any> ?: mapOf()

                synchronized(featureFlagsLock) {
                    if (!errorsWhileComputingFlags) {
                        this.featureFlags = (this.featureFlags ?: mapOf()) + featureFlags
                        this.featureFlagPayloads = (this.featureFlagPayloads ?: mapOf()) + featureFlagPayloads
                    } else {
                        this.featureFlags = featureFlags
                        this.featureFlagPayloads = featureFlagPayloads
                    }
                }

                isFeatureFlagsLoaded = true
            } catch (e: Throwable) {
                isFeatureFlagsLoaded = false
                config.logger.log("Loading feature flags failed: $e")
            }

            isLoadingFeatureFlags.set(false)
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