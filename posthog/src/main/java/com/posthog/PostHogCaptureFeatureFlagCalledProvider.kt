package com.posthog

@PostHogInternal
public fun interface PostHogCaptureFeatureFlagCalledProvider {
    public fun onCaptureFeatureFlagCalled(
        flagKey: String,
        flagValue: Any?,
    )
}
