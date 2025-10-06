package com.posthog.internal

import com.posthog.PostHogInternal
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

/**
 * The response data structure for calling the /api/feature_flag/local_evaluation API
 * @property flags the feature flag definitions for local evaluation
 * @property groupTypeMapping the mapping of group type IDs to group types
 * @property cohorts the cohort definitions for local evaluation
 */
@IgnoreJRERequirement
@PostHogInternal
public data class PostHogLocalEvaluationResponse(
    val flags: List<Map<String, Any?>>?,
    val groupTypeMapping: Map<String, String>?,
    val cohorts: Map<String, Map<String, Any?>>?,
) : PostHogRemoteConfigResponse()
