package com.posthog.internal

import com.posthog.PostHogInternal
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

/**
 * The feature flag data structure for calling the /flags API v2. This is what each flag in "flags" will be.
 * @property key the key of the feature flag
 * @property enabled whether the feature flag is enabled
 * @property variant the variant of the feature flag
 * @property metadata the metadata of the feature flag
 * @property reason the reason the feature flag was evaluated
 */
@IgnoreJRERequirement
@PostHogInternal
public data class FeatureFlag(
    val key: String,
    val enabled: Boolean,
    val variant: String?,
    val metadata: FeatureFlagMetadata,
    val reason: EvaluationReason?,
)
