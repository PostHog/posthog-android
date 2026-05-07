package com.posthog.android

import com.posthog.PostHogConfig
import com.posthog.android.replay.PostHogReplayQueue
import com.posthog.android.replay.PostHogSessionReplayConfig
import com.posthog.internal.PostHogApiEndpoint
import com.posthog.internal.PostHogQueue

/**
 * The SDK Config
 * @property apiKey the PostHog API Key
 * @property captureApplicationLifecycleEvents captures lifecycle events such as app installed, app updated, app opened and backgrounded
 * @property captureDeepLinks captures deep links events
 * @property captureScreenViews captures screen views events
 */
public open class PostHogAndroidConfig
    @JvmOverloads
    constructor(
        apiKey: String?,
        host: String = DEFAULT_HOST,
        public var captureApplicationLifecycleEvents: Boolean = true,
        public var captureDeepLinks: Boolean = true,
        public var captureScreenViews: Boolean = true,
        public var sessionReplayConfig: PostHogSessionReplayConfig = PostHogSessionReplayConfig(),
    ) : PostHogConfig(
            apiKey = apiKey,
            host = host,
            queueProvider = { config, api, endpoint, storagePrefix, executor ->
                val defaultQueue = PostHogQueue(config, api, endpoint, storagePrefix, executor)
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
