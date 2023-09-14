package com.posthog

public class PostHogConfig(
    public var apiKey: String,
    public var host: String = "https://app.posthog.com",
    public var debug: Boolean = false,
    public var flushAt: Int = 20,
    public var maxQueueSize: Int = 1000,
    public var maxBatchSize: Int = 10,
//    (30).toDuration(DurationUnit.SECONDS) requires Kotlin 1.6
    public var flushIntervalSeconds: Int = 30,
    public var dataMode: PostHogDataMode = PostHogDataMode.ANY,
    public var storagePrefix: String? = null,
) {
    internal var logger: PostHogLogger? = null

    // TODO: read the repo name and version
    internal var userAgent: String = "posthog-android/3.0.0"
}
