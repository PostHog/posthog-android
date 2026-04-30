package com.posthog.server.internal

import com.posthog.internal.FeatureFlag

/**
 * Cache entry containing feature flag data and expiry information.
 *
 * @property error Pre-computed error string from the API response or request failure.
 *                 This is null when there are no errors. For successful responses with
 *                 server-side issues, this contains comma-separated error types like
 *                 "errors_while_computing_flags,quota_limited". For request failures,
 *                 this contains the failure type like "timeout" or "api_error_500".
 *                 Note: "flag_missing" is computed at query time since it depends on
 *                 the specific flag key being requested.
 */
internal data class FeatureFlagCacheEntry(
    val flags: Map<String, FeatureFlag>?,
    val timestamp: Long,
    val expiresAt: Long,
    val requestId: String? = null,
    val evaluatedAt: Long? = null,
    val error: String? = null,
) {
    /**
     * Check if this cache entry has expired
     */
    fun isExpired(currentTime: Long = System.currentTimeMillis()): Boolean {
        return currentTime >= expiresAt
    }
}
