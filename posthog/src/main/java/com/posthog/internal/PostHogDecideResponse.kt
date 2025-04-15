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

/**
 * The feature flag data structure for calling the decide API v4. This is what each flag in "flags" will be.
 * @property key the key of the feature flag
 * @property enabled whether the feature flag is enabled
 * @property variant the variant of the feature flag
 * @property metadata the metadata of the feature flag
 * @property reason the reason the feature flag was evaluated
 */
@IgnoreJRERequirement
internal data class FeatureFlag(
    val key: String,
    val enabled: Boolean,
    val variant: String?,
    val metadata: FeatureFlagMetadata,
    val reason: EvaluationReason?,
)

/**
 * The metadata of the feature flag
 * @property id the id of the feature flag
 * @property payload the payload of the feature flag
 * @property version the version of the feature flag
 */
@IgnoreJRERequirement
internal data class FeatureFlagMetadata(
    val id: Int,
    val payload: String?,
    val version: Int,
)

@IgnoreJRERequirement
internal data class EvaluationReason(
    val code: String?,
    val description: String?,
    val condition_index: Int?,
)