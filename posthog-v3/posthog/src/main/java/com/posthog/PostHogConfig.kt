package com.posthog

import com.posthog.internal.PostHogLogger

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
    public var encryption: PostHogEncryption = PostHogEncryption.PostHogEncryptionNone()
) {
    internal var logger: PostHogLogger? = null

    // TODO: read the repo name and version
    internal var userAgent: String = "posthog-android/3.0.0"

    // should this be configurable by the user?
    internal var storagePrefix: String? = null
}
