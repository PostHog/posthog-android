package com.posthog.internal

import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

/**
 * The decide response data structure for calling the decide API
 * @property errorsWhileComputingFlags if there were errors computing feature flags
 * @property featureFlags the feature flags
 * @property featureFlagPayloads the feature flag payloads
 * @property quotaLimited array of quota limited features
 */
@IgnoreJRERequirement
internal data class PostHogDecideResponse(
    // assuming theres no errors if not present
    val errorsWhileComputingFlags: Boolean = false,
    val featureFlags: Map<String, Any>?,
    val featureFlagPayloads: Map<String, Any?>?,
    val quotaLimited: List<String>? = null,
) : PostHogRemoteConfigResponse()
