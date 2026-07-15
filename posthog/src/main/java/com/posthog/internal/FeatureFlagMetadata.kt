package com.posthog.internal

import com.google.gson.annotations.SerializedName
import com.posthog.PostHogInternal
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

/**
 * The metadata for a feature flag returned from the /flags v2 API
 * @property id the id of the feature flag
 * @property payload the payload of the feature flag
 * @property version the version of the feature flag
 * @property hasExperiment whether the flag is linked to an experiment; null when the server
 * does not report the field (older deployments)
 */
@IgnoreJRERequirement
@PostHogInternal
public data class FeatureFlagMetadata(
    val id: Int,
    val payload: String?,
    val version: Int,
    @SerializedName("has_experiment")
    val hasExperiment: Boolean? = null,
)
