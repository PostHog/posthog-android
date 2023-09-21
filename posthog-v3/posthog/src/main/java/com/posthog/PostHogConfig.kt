package com.posthog

import com.posthog.internal.PostHogNetworkStatus

public class PostHogConfig(
    // apiKey and host are immutable due to offline caching
    public val apiKey: String,
    public val host: String = "https://app.posthog.com",
    public var debug: Boolean = false,
    public var optOut: Boolean = false,
    public var sendFeatureFlagEvent: Boolean = true,
    public var preloadFeatureFlags: Boolean = true,
    // min. allowed is 1
    public var flushAt: Int = 20,
    public var maxQueueSize: Int = 1000,
    public var maxBatchSize: Int = 10,
//    (30).toDuration(DurationUnit.SECONDS) requires Kotlin 1.6
    public var flushIntervalSeconds: Int = 30,

    public var dataMode: PostHogDataMode = PostHogDataMode.ANY,
    public var encryption: PostHogEncryption? = null,
    public val integrations: MutableList<PostHogIntegration> = mutableListOf(),
) {
    @PostHogInternal
    public var logger: PostHogLogger = PostHogPrintLogger(this)

    @PostHogInternal
    public var context: PostHogContext? = null

    // TODO: read the repo name and version
    @PostHogInternal
    public var sdkName: String = "posthog-android"

    @PostHogInternal
    public var sdkVersion: String = "3.0.0"

    internal val userAgent: String = "$sdkName/$sdkVersion"

    // should this be configurable by the user?
    @PostHogInternal
    public var legacyStoragePrefix: String? = null

    @PostHogInternal
    public var storagePrefix: String? = null

    @PostHogInternal
    public var preferences: PostHogPreferences? = null

    @PostHogInternal
    public var networkStatus: PostHogNetworkStatus? = null
}
