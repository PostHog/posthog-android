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
    /**
     * Combined response-level error string (e.g. "errors_while_computing_flags",
     * "quota_limited", or both joined by ","). Propagated to `$feature_flag_error` on snapshot
     * events so they match what the per-flag accessor path emits.
     */
    val responseError: String?,
)
