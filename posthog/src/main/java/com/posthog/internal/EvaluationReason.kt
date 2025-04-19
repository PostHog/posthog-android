package com.posthog.internal

import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

/**
 * The reason the feature flag was evaluated as returned from the decide v4 API
 * @property code the code of the reason
 * @property description the description of the reason
 * @property condition_index the condition index of the reason
 */
@IgnoreJRERequirement
internal data class EvaluationReason(
    val code: String?,
    val description: String?,
    val condition_index: Int?,
)
