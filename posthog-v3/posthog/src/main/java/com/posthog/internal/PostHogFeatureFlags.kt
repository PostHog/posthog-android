package com.posthog.internal

import com.posthog.PostHogConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// TODO: ENABLED_FEATURE_FLAGS_KEY

internal class PostHogFeatureFlags(private val config: PostHogConfig, private val api: PostHogApi) {
    private val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("PostHogDecideThread"))

    private var isLoadingFeatureFlags = AtomicBoolean(false)

    private val featureFlagsLock = Any()

    private var featureFlags: Map<String, Any>? = null
    private var featureFlagPayloads: Map<String, Any>? = null

    @Volatile
    private var isFeatureFlagsLoaded = false

    fun loadFeatureFlags(properties: Map<String, Any>) {
        executor.execute {
            if (isLoadingFeatureFlags.getAndSet(true)) {
                config.logger.log("Feature flags are being loaded already.")
                return@execute
            }

            try {
                val result = api.decide(properties)
                val featureFlags = result?.get("featureFlags") as? Map<String, Any>
                val featureFlagPayloads = result?.get("featureFlagPayloads") as? Map<String, Any>

                synchronized(featureFlagsLock) {
                    this.featureFlags = featureFlags
                    this.featureFlagPayloads = featureFlagPayloads
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
            // TODO: read featureFlags and featureFlagPayloads only?
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
}
