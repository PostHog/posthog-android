package com.posthog.android

import com.posthog.PostHogConfig

public class PostHogAndroidConfig(
    apiKey: String,
    public var captureApplicationLifecycleEvents: Boolean = true,
    public var captureDeepLinks: Boolean = true,
    public var captureRecordScreenViews: Boolean = true,
) : PostHogConfig(apiKey)
