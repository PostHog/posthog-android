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
    // TODO: replay returns endpoint in response, see https://github.com/PostHog/posthog-js/blob/10fd7f4fa083f997d31a4a4c7be7d311d0a95e74/src/types.ts#L235-L243
    // add others

    // rules if theres a client and server side config
    // // when client side is opted out, it is always off
// // when client side is opted in, it is only on, if the remote does not opt out
    // example https://github.com/PostHog/posthog-js/blob/master/src/__tests__/autocapture.js#L1157-L1175
)
