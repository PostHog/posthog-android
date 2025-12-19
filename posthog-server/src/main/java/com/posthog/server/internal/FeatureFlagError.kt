package com.posthog.server.internal

/**
 * Error type constants for the $feature_flag_error property.
 *
 * These values are sent in analytics events to track flag evaluation failures.
 * They should not be changed without considering impact on existing dashboards
 * and queries that filter on these values.
 *
 * Error values:
 *   ERRORS_WHILE_COMPUTING: Server returned errorsWhileComputingFlags=true
 *   FLAG_MISSING: Requested flag not in API response
 *   QUOTA_LIMITED: Rate/quota limit exceeded
 *   TIMEOUT: Request timed out
 *   CONNECTION_ERROR: Network connectivity issue
 *   UNKNOWN_ERROR: Unexpected exceptions
 *
 * For API errors with status codes, use apiError(status) which returns "api_error_XXX".
 */
internal object FeatureFlagError {
    const val ERRORS_WHILE_COMPUTING: String = "errors_while_computing_flags"
    const val FLAG_MISSING: String = "flag_missing"
    const val QUOTA_LIMITED: String = "quota_limited"
    const val TIMEOUT: String = "timeout"
    const val CONNECTION_ERROR: String = "connection_error"
    const val UNKNOWN_ERROR: String = "unknown_error"

    /**
     * Generate API error string with status code.
     * Returns a string like "api_error_500".
     */
    fun apiError(status: Int): String = "api_error_$status"
}
