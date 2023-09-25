package com.posthog

import PostHog.posthog.BuildConfig

public open class PostHogConfig(
    // apiKey and host are immutable due to offline caching
    public val apiKey: String,
    public val host: String = "https://app.posthog.com",
    public var debug: Boolean = false,
    @Volatile
    public var optOut: Boolean = false,
    public var sendFeatureFlagEvent: Boolean = true,
    public var preloadFeatureFlags: Boolean = true,
    // min. allowed is 1
    public var flushAt: Int = 20, // TODO: remove this one
    public var maxQueueSize: Int = 1000,
    public var maxBatchSize: Int = 50,
//    (30).toDuration(DurationUnit.SECONDS) requires Kotlin 1.6
    public var flushIntervalSeconds: Int = 30,

    public var encryption: PostHogEncryption? = null,
) {
    @PostHogInternal
    public var logger: PostHogLogger = PostHogPrintLogger(this)

    @PostHogInternal
    public var context: PostHogContext? = null

    @PostHogInternal
    public val sdkName: String = "posthog-android"

    @PostHogInternal
    public var sdkVersion: String = BuildConfig.VERSION_NAME

    internal val userAgent: String = "$sdkName/$sdkVersion"

    @PostHogInternal
    public var legacyStoragePrefix: String? = null

    @PostHogInternal
    public var storagePrefix: String? = null

    @PostHogInternal
    public var cachePreferences: PostHogPreferences? = null

    @PostHogInternal
    public var networkStatus: PostHogNetworkStatus? = null

    private val integrationsList: MutableList<PostHogIntegration> = mutableListOf()
    private val integrationLock = Any()

    public val integrations: List<PostHogIntegration>
        get() {
            val list: List<PostHogIntegration>
            synchronized(integrationLock) {
                list = integrationsList.toList()
            }
            return list
        }

    public fun addIntegration(integration: PostHogIntegration) {
        synchronized(integrationLock) {
            integrationsList.add(integration)
        }
    }

    public fun removeIntegration(integration: PostHogIntegration) {
        synchronized(integrationLock) {
            integrationsList.remove(integration)
        }
    }
}
