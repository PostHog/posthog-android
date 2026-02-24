package com.posthog



@PostHogInternal
public fun interface PostHogCaptureFeatureFlagCalledProvider {
    public fun onCaptureFeatureFlagCalled(info: Pair<String, Any?>)
}