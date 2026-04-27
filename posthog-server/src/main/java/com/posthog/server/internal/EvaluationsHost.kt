package com.posthog.server.internal

/**
 * The minimal slice of the SDK that [com.posthog.server.PostHogFeatureFlagEvaluations] depends on.
 * The snapshot calls back into the host to fire deduped `$feature_flag_called` events and to log
 * filter warnings, but does not need a reference to the full client. This keeps the snapshot easy
 * to test in isolation with a fake host.
 */
internal interface EvaluationsHost {
    val warningsEnabled: Boolean

    fun captureFeatureFlagCalled(
        distinctId: String,
        key: String,
        value: Any?,
        properties: Map<String, Any>,
    )

    fun logWarning(message: String)
}
