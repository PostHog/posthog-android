package com.posthog.internal

import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

/**
 * The metadata for a feature flag returned from the decide v4 API
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
