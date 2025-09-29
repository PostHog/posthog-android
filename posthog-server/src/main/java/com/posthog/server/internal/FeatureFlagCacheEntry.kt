package com.posthog.server.internal

import com.posthog.internal.FeatureFlag

/**
 * Cache entry containing feature flag data and expiry information
 */
internal data class FeatureFlagCacheEntry(
    val flags: Map<String, FeatureFlag>?,
    val timestamp: Long,
    val expiresAt: Long,
) {
    /**
     * Check if this cache entry has expired
     */
    fun isExpired(currentTime: Long = System.currentTimeMillis()): Boolean {
        return currentTime >= expiresAt
    }

    override fun hashCode(): Int {
        var result = flags?.hashCode() ?: 0
        result = 31 * result + (timestamp xor (timestamp ushr 32)).toInt()
        result = 31 * result + (expiresAt xor (expiresAt ushr 32)).toInt()
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FeatureFlagCacheEntry) return false

        if (flags != other.flags) return false
        if (timestamp != other.timestamp) return false
        if (expiresAt != other.expiresAt) return false

        return true
    }
}
