package com.posthog.android

import com.posthog.PostHogConfig
import com.posthog.PostHogExperimental
import com.posthog.android.replay.PostHogSessionReplayConfig

/**
 * The SDK Config
 * @property apiKey the PostHog API Key
 * @property captureApplicationLifecycleEvents captures lifecycle events such as app installed, app updated, app opened and backgrounded
 * @property captureDeepLinks captures deep links events
 * @property captureScreenViews captures screen views events
 */
public open class PostHogAndroidConfig(
    apiKey: String,
    host: String = defaultHost,
    public var captureApplicationLifecycleEvents: Boolean = true,
    public var captureDeepLinks: Boolean = true,
    public var captureScreenViews: Boolean = true,
    @PostHogExperimental
    public var sessionReplayConfig: PostHogSessionReplayConfig = PostHogSessionReplayConfig(),
) : PostHogConfig(apiKey, host)
