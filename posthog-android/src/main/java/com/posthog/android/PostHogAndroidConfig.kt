package com.posthog.android

import com.posthog.PostHogConfig

public open class PostHogAndroidConfig(
    apiKey: String,
    public var captureApplicationLifecycleEvents: Boolean = true,
    public var captureDeepLinks: Boolean = true,
    public var captureScreenViews: Boolean = true,
) : PostHogConfig(apiKey)
