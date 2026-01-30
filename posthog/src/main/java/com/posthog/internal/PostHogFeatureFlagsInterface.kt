package com.posthog.internal

import com.posthog.FeatureFlagResult
import com.posthog.PostHogInternal

@PostHogInternal
public interface PostHogFeatureFlagsInterface {
    public fun getFeatureFlagResult(
        key: String,
        distinctId: String? = null,
        groups: Map<String, String>? = null,
        personProperties: Map<String, Any?>? = null,
        groupProperties: Map<String, Map<String, Any?>>? = null,
    ): FeatureFlagResult?

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

    public fun getRequestId(
        distinctId: String? = null,
        groups: Map<String, String>? = null,
        personProperties: Map<String, Any?>? = null,
        groupProperties: Map<String, Map<String, Any?>>? = null,
    ): String?

    public fun getEvaluatedAt(
        distinctId: String? = null,
        groups: Map<String, String>? = null,
        personProperties: Map<String, Any?>? = null,
        groupProperties: Map<String, Map<String, Any?>>? = null,
    ): Long?

    public fun getFeatureFlagError(
        key: String,
        distinctId: String? = null,
        groups: Map<String, String>? = null,
        personProperties: Map<String, Any?>? = null,
        groupProperties: Map<String, Map<String, Any?>>? = null,
    ): String? {
        return null
    }
}
