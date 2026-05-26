package com.posthog.android

import com.posthog.PostHogConfig
import com.posthog.android.replay.PostHogReplayQueue
import com.posthog.android.replay.PostHogSessionReplayConfig
import com.posthog.internal.EndpointSpec
import com.posthog.internal.PostHogApiEndpoint
import com.posthog.internal.PostHogQueue

/**
 * The SDK Config
 * @property apiKey the PostHog API Key
 * @property captureApplicationLifecycleEvents captures lifecycle events such as app installed, app updated, app opened and backgrounded
 * @property captureDeepLinks captures deep links events
 * @property captureScreenViews automatically captures a `$screen` event whenever a foreground
 * Activity starts (via `ActivityLifecycleCallbacks.onActivityStarted`). When enabled, the most
 * recent screen name is also attached as `$screen_name` to every subsequent event captured by
 * the SDK. To opt out of `$screen_name` stamping entirely, set to `false` AND avoid calling
 * `PostHog.screen(...)` manually. Default: `true`.
 */
public open class PostHogAndroidConfig
    @JvmOverloads
    constructor(
        apiKey: String,
        host: String = DEFAULT_HOST,
        public var captureApplicationLifecycleEvents: Boolean = true,
        public var captureDeepLinks: Boolean = true,
        public var captureScreenViews: Boolean = true,
        public var sessionReplayConfig: PostHogSessionReplayConfig = PostHogSessionReplayConfig(),
    ) : PostHogConfig(
            apiKey = apiKey,
            host = host,
            queueProvider = { config, api, endpoint, storagePrefix, executor ->
                val spec =
                    when (endpoint) {
                        PostHogApiEndpoint.BATCH -> EndpointSpec.batch(config, api, storagePrefix)
                        PostHogApiEndpoint.SNAPSHOT -> EndpointSpec.snapshot(config, api, storagePrefix)
                    }
                val defaultQueue = PostHogQueue(config, spec, executor)
                if (endpoint == PostHogApiEndpoint.SNAPSHOT) {
                    val replayQueue = PostHogReplayQueue(config, defaultQueue, storagePrefix, executor)
                    (config as? PostHogAndroidConfig)?.replayQueueHolder = replayQueue
                    replayQueue
                } else {
                    defaultQueue
                }
            },
        ) {
        internal var replayQueueHolder: PostHogReplayQueue? = null
    }
