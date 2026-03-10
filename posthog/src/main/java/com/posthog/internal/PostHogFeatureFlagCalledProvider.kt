package com.posthog.internal

import com.posthog.PostHogInternal

@PostHogInternal
public fun interface PostHogFeatureFlagCalledProvider {
    public fun onFeatureFlagCalled(
        flagKey: String,
        flagValue: Any?,
    )
}
