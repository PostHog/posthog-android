package com.posthog.internal

import com.posthog.PostHogConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class PostHogFeatureFlags(private val config: PostHogConfig, private val api: PostHogApi) {
    private val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("PostHogDecideThread"))

    private var isLoadingFeatureFlags = AtomicBoolean(false)

    private val featureFlagsLock = Any()

    private var featureFlags: Map<String, Any>? = null

    @Volatile
    private var isFeatureFlagsLoaded = false

    fun loadFeatureFlagsRequest(properties: Map<String, Any>) {
        executor.execute {
            if (isLoadingFeatureFlags.getAndSet(true)) {
                config.logger?.log("Feature flags are being loaded already.")
                return@execute
            }

            try {
                val featureFlags = api.decide(properties)

                synchronized(featureFlagsLock) {
                    this.featureFlags = featureFlags
                }

                isFeatureFlagsLoaded = true
            } catch (e: Throwable) {
                config.logger?.log("Loading feature flags failed: $e")
            }

            isLoadingFeatureFlags.set(false)
        }
    }

    fun isFeatureEnabled(key: String, defaultValue: Boolean = false): Boolean {
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
}
