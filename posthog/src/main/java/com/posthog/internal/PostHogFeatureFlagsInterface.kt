package com.posthog.internal

import com.posthog.PostHogInternal

@PostHogInternal
public interface PostHogFeatureFlagsInterface {
    public fun getFeatureFlag(
        key: String,
        defaultValue: Any?,
        distinctId: String? = null,
        groups: Map<String, String>? = null,
        personProperties: Map<String, Any?>? = null,
        groupProperties: Map<String, Map<String, Any?>>? = null,
    ): Any?

    public fun getFeatureFlagPayload(
        key: String,
        defaultValue: Any?,
        distinctId: String? = null,
        groups: Map<String, String>? = null,
        personProperties: Map<String, Any?>? = null,
        groupProperties: Map<String, Map<String, Any?>>? = null,
    ): Any?

    public fun getFeatureFlags(
        distinctId: String? = null,
        groups: Map<String, String>? = null,
        personProperties: Map<String, Any?>? = null,
        groupProperties: Map<String, Map<String, Any?>>? = null,
    ): Map<String, Any>?

    public fun clear()

    public fun shutDown() {
        // no-op by default
    }
}
