package com.posthog.server.internal

import com.posthog.internal.FeatureFlag

/**
 * LRU cache with TTL support for feature flag responses
 */
internal class PostHogFeatureFlagCache(
    private val maxSize: Int,
    private val maxAgeMs: Int,
) {
    private val cache =
        object : LinkedHashMap<FeatureFlagCacheKey, FeatureFlagCacheEntry>(
            16,
            0.75f,
            true,
        ) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FeatureFlagCacheKey, FeatureFlagCacheEntry>?): Boolean {
                return size > maxSize
            }
        }

    /**
     * Get feature flags from cache if present and not expired
     */
    @Synchronized
    fun get(key: FeatureFlagCacheKey): Map<String, FeatureFlag>? {
        val entry = cache[key]
        if (entry == null) {
            return null
        }

        if (entry.isExpired()) {
            cache.remove(key)
            return null
        }

        return entry.flags
    }

    /**
     * Put feature flags into cache with current timestamp
     */
    @Synchronized
    fun put(
        key: FeatureFlagCacheKey,
        flags: Map<String, FeatureFlag>?,
    ) {
        val currentTime = System.currentTimeMillis()
        val entry =
            FeatureFlagCacheEntry(
                flags = flags,
                timestamp = currentTime,
                expiresAt = currentTime + maxAgeMs,
            )

        cache[key] = entry
    }

    /**
     * Clear all cached entries
     */
    @Synchronized
    fun clear() {
        cache.clear()
    }

    /**
     * Get current cache size
     */
    @Synchronized
    fun size(): Int = cache.size
}
