package com.posthog

public class PostHogConfig(
    // apiKey and host are immutable due to offline caching
    public val apiKey: String,
    public val host: String = "https://app.posthog.com",
    public var debug: Boolean = false,
    public var flushAt: Int = 20,
    public var maxQueueSize: Int = 1000,
    public var maxBatchSize: Int = 10,
//    (30).toDuration(DurationUnit.SECONDS) requires Kotlin 1.6
    public var flushIntervalSeconds: Int = 30,

    public var dataMode: PostHogDataMode = PostHogDataMode.ANY,
    public var encryption: PostHogEncryption = PostHogEncryption.PostHogEncryptionNone(),
    public val integrations: MutableList<PostHogIntegration> = mutableListOf(),
) {
    @PostHogInternal
    public var logger: PostHogLogger = PostHogPrintLogger(this)
    public var context: PostHogContext? = null

    // TODO: read the repo name and version
    internal var userAgent: String = "posthog-android/3.0.0"

    // should this be configurable by the user?
    @PostHogInternal
    public var legacyStoragePrefix: String? = null

    @PostHogInternal
    public var storagePrefix: String? = null
}
