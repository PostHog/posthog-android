package com.posthog.internal

import com.posthog.PostHogOnFeatureFlags

internal interface PostHogFeatureFlagsInterface {
    fun loadFeatureFlags(
        distinctId: String,
        anonymousId: String?,
        groups: Map<String, Any>?,
        onFeatureFlags: PostHogOnFeatureFlags?,
    )

    fun isFeatureEnabled(
        key: String,
        defaultValue: Boolean,
        distinctId: String,
        anonymousId: String?,
        groups: Map<String, Any>?,
    ): Boolean

    fun getFeatureFlagPayload(
        key: String,
        defaultValue: Any?,
        distinctId: String,
        anonymousId: String?,
        groups: Map<String, Any>?,
    ): Any?

    fun getFeatureFlag(
        key: String,
        defaultValue: Any?,
        distinctId: String,
        anonymousId: String?,
        groups: Map<String, Any>?,
    ): Any?

    fun getFeatureFlags(
        distinctId: String,
        anonymousId: String?,
        groups: Map<String, Any>?,
    ): Map<String, Any>?

    fun clear()

    fun normalizePayloads(
        serializer: PostHogSerializer,
        featureFlagPayloads: Map<String, Any?>?,
    ): Map<String, Any?> {
        val parsedPayloads = (featureFlagPayloads ?: mapOf()).toMutableMap()

        for (item in parsedPayloads) {
            val value = item.value

            try {
                // only try to parse if it's a String, since the JSON values are stringified
                if (value is String) {
                    // try to deserialize as Any?
                    serializer.deserializeString(value)?.let {
                        parsedPayloads[item.key] = it
                    }
                }
            } catch (ignored: Throwable) {
                // if it fails, we keep the original value
            }
        }
        return parsedPayloads
    }

    fun normalizeBoolean(
        value: Any?,
        defaultValue: Boolean,
    ): Boolean {
        return if (value != null) {
            if (value is Boolean) {
                value
            } else {
                // if its multivariant flag, its enabled by default
                true
            }
        } else {
            defaultValue
        }
    }
}
