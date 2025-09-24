package com.posthog.internal

import com.posthog.PostHogOnFeatureFlags

public interface PostHogFeatureFlagsInterface {
    public fun loadRemoteConfig(
        distinctId: String,
        anonymousId: String?,
        groups: Map<String, String>?,
        internalOnFeatureFlags: PostHogOnFeatureFlags? = null,
        onFeatureFlags: PostHogOnFeatureFlags? = null,
    )

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
