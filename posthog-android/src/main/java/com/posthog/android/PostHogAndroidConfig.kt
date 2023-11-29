package com.posthog.android

import com.posthog.PostHogConfig

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
    // TODO: better to create a new class for all Replay config
    public var maskAllInputs: Boolean = true,
) : PostHogConfig(apiKey, host)
