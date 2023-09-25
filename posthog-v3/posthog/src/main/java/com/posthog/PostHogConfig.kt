package com.posthog

import PostHog.posthog.BuildConfig
import com.posthog.internal.PostHogNetworkStatus

public class PostHogConfig(
    // apiKey and host are immutable due to offline caching
    public val apiKey: String,
    public val host: String = "https://app.posthog.com",
    public var debug: Boolean = false,
    @Volatile
    public var optOut: Boolean = false,
    public var sendFeatureFlagEvent: Boolean = true,
    public var preloadFeatureFlags: Boolean = true,
    // min. allowed is 1
    public var flushAt: Int = 20,
    public var maxQueueSize: Int = 1000,
    public var maxBatchSize: Int = 10,
//    (30).toDuration(DurationUnit.SECONDS) requires Kotlin 1.6
    public var flushIntervalSeconds: Int = 30,

    public var encryption: PostHogEncryption? = null,
    public val integrations: MutableList<PostHogIntegration> = mutableListOf(),
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

    // TODO: should this be configurable by the user?
    // should we allow in memory cache only?
    @PostHogInternal
    public var legacyStoragePrefix: String? = null

    @PostHogInternal
    public var storagePrefix: String? = null

    @PostHogInternal
    public var cachePreferences: PostHogPreferences? = null

    @PostHogInternal
    public var networkStatus: PostHogNetworkStatus? = null
}
