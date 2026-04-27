package com.posthog.server.internal

import com.posthog.internal.FeatureFlag

/**
 * The rich envelope returned by [PostHogFeatureFlags.evaluateFlags]. Holds the per-flag results
 * plus the request-scoped metadata that the snapshot exposes on `$feature_flag_called` events.
 */
internal data class EvaluateFlagsResult(
    val flags: Map<String, FeatureFlag>,
    val locallyEvaluated: Map<String, Boolean>,
    val requestId: String?,
    val evaluatedAt: Long?,
    val definitionsLoadedAt: Long?,
)
