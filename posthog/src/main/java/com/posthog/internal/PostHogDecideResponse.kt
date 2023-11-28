package com.posthog.internal

/**
 * The decide response data structure for calling the decide API
 * @property errorsWhileComputingFlags if there were errors computing feature flags
 * @property featureFlags the feature flags
 * @property featureFlagPayloads the feature flag payloads
 */
internal data class PostHogDecideResponse(
    // assuming theres no errors if not present
    val errorsWhileComputingFlags: Boolean = false,
    val featureFlags: Map<String, Any>?,
    val featureFlagPayloads: Map<String, Any?>?,
    // its either a boolean or a map, see https://github.com/PostHog/posthog-js/blob/10fd7f4fa083f997d31a4a4c7be7d311d0a95e74/src/types.ts#L235-L243
    val sessionRecording: Any? = false,
)
