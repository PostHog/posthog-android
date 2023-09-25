package com.posthog

public fun interface PostHogOnFeatureFlags {
    public fun invoke(featureFlags: Map<String, Any>?)
}
