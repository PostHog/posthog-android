package com.posthog.internal

import com.posthog.PostHogInternal

@PostHogInternal
public interface PostHogFeatureFlagsInterface {
    public fun getFeatureFlag(
        key: String,
        defaultValue: Any?,
        distinctId: String? = null,
        groups: Map<String, String>? = null,
        personProperties: Map<String, String>? = null,
        groupProperties: Map<String, String>? = null,
    ): Any?

    public fun getFeatureFlagPayload(
        key: String,
        defaultValue: Any?,
        distinctId: String? = null,
        groups: Map<String, String>? = null,
        personProperties: Map<String, String>? = null,
        groupProperties: Map<String, String>? = null,
    ): Any?

    public fun getFeatureFlags(
        distinctId: String? = null,
        groups: Map<String, String>? = null,
        personProperties: Map<String, String>? = null,
        groupProperties: Map<String, String>? = null,
    ): Map<String, Any>?

    public fun clear()
}
