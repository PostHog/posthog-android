package com.posthog.internal

import com.posthog.PostHogOnFeatureFlags

public interface PostHogFeatureFlagsInterface {
    public fun loadFeatureFlags(
        distinctId: String,
        anonymousId: String?,
        groups: Map<String, String>?,
        onFeatureFlags: PostHogOnFeatureFlags?,
    )

    public fun isFeatureEnabled(
        key: String,
        defaultValue: Boolean,
    ): Boolean

    public fun getFeatureFlag(
        key: String,
        defaultValue: Any?,
    ): Any?

    public fun getFeatureFlagPayload(
        key: String,
        defaultValue: Any?,
    ): Any?

    public fun getFeatureFlags(): Map<String, Any>?

    public fun isSessionReplayFlagActive(): Boolean

    public fun clear()
}
