package com.posthog.server.internal

import com.posthog.internal.FeatureFlag

/**
 * LRU cache with TTL support for feature flag responses
 */
internal class PostHogFeatureFlagCache(
    private val maxSize: Int,
    private val maxAgeMs: Int,
) {
    private val cache = mutableMapOf<FeatureFlagCacheKey, FeatureFlagCacheEntry>()
    private val accessOrder = mutableListOf<FeatureFlagCacheKey>()

    /**
     * Get feature flags from cache if present and not expired
     */
    @Synchronized
    fun get(key: FeatureFlagCacheKey): Map<String, FeatureFlag>? {
        cleanupExpiredEntries()

        val entry = cache[key]
        if (entry == null) {
            return null
        }

        if (entry.isExpired()) {
            removeFromCache(key)
            return null
        }

        // Move to end (most recently used)
        accessOrder.remove(key)
        accessOrder.add(key)

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

        // Remove if already exists to update access order
        if (cache.containsKey(key)) {
            accessOrder.remove(key)
        }

        cache[key] = entry
        accessOrder.add(key)

        // Remove eldest entries if over max size
        while (accessOrder.size > maxSize) {
            val eldestKey = accessOrder.removeAt(0)
            cache.remove(eldestKey)
        }
    }

    /**
     * Clear all cached entries
     */
    @Synchronized
    fun clear() {
        cache.clear()
        accessOrder.clear()
    }

    /**
     * Get current cache size
     */
    @Synchronized
    fun size(): Int = cache.size

    /**
     * Remove a key from cache and access order
     */
    private fun removeFromCache(key: FeatureFlagCacheKey) {
        cache.remove(key)
        accessOrder.remove(key)
    }

    /**
     * Remove expired entries from cache
     */
    private fun cleanupExpiredEntries() {
        val currentTime = System.currentTimeMillis()
        val expiredKeys = mutableListOf<FeatureFlagCacheKey>()

        for ((key, entry) in cache) {
            if (entry.isExpired(currentTime)) {
                expiredKeys.add(key)
            }
        }

        for (key in expiredKeys) {
            removeFromCache(key)
        }
    }
}
