package com.posthog.internal

import com.posthog.PostHogInternal
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

/**
 * The reason the feature flag was evaluated as returned from the flags v2 API
 * @property code the code of the reason
 * @property description the description of the reason
 * @property condition_index the condition index of the reason
 */
@IgnoreJRERequirement
@PostHogInternal
public data class EvaluationReason(
    val code: String?,
    val description: String?,
    val condition_index: Int?,
)
