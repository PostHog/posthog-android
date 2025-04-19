package com.posthog.internal

import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

/**
 * The decide response data structure for calling the decide API
 * @property errorsWhileComputingFlags if there were errors computing feature flags
 * @property featureFlags the feature flags
 * @property featureFlagPayloads the feature flag payloads
 * @property flags the decide v4 flags.
 * @property quotaLimited array of quota limited features
 */
@IgnoreJRERequirement
internal data class PostHogDecideResponse(
    // assuming theres no errors if not present
    val errorsWhileComputingFlags: Boolean = false,
    val featureFlags: Map<String, Any>?,
    val featureFlagPayloads: Map<String, Any?>?,
    val flags: Map<String, FeatureFlag>? = null,
    val quotaLimited: List<String>? = null,
    val requestId: String?,
) : PostHogRemoteConfigResponse()
