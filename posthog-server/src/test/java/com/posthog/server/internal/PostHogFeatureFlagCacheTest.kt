package com.posthog.server.internal

import com.posthog.internal.EvaluationReason
import com.posthog.internal.FeatureFlag
import com.posthog.internal.FeatureFlagMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogFeatureFlagCacheTest {
    private fun createTestFlags(): Map<String, FeatureFlag> {
        val metadata = FeatureFlagMetadata(1, "test-payload", 1)
        val reason = EvaluationReason("condition_match", "Test condition", 0)
        return mapOf(
            "test-flag" to FeatureFlag("test-flag", true, null, metadata, reason),
            "another-flag" to FeatureFlag("another-flag", false, null, metadata, reason),
        )
    }

    private fun createTestKey(
        distinctId: String = "test-user",
        groups: Map<String, String>? = null,
        personProperties: Map<String, Any?>? = null,
        groupProperties: Map<String, Map<String, Any?>>? = null,
    ): FeatureFlagCacheKey {
        return FeatureFlagCacheKey(distinctId, groups, personProperties, groupProperties)
    }

    @Test
    fun `put and get basic functionality`() {
        val cache = PostHogFeatureFlagCache(maxSize = 10, maxAgeMs = 60000)
        val key = createTestKey()
        val flags = createTestFlags()

        cache.put(key, flags)
        val retrieved = cache.get(key)

        assertEquals(flags, retrieved)
        assertEquals(1, cache.size())
    }

    @Test
    fun `get returns null for non-existent key`() {
        val cache = PostHogFeatureFlagCache(maxSize = 10, maxAgeMs = 60000)
        val key = createTestKey()

        val retrieved = cache.get(key)

        assertNull(retrieved)
        assertEquals(0, cache.size())
    }

    @Test
    fun `put null flags stores entry correctly`() {
        val cache = PostHogFeatureFlagCache(maxSize = 10, maxAgeMs = 60000)
        val key = createTestKey()

        cache.put(key, null)
        val retrieved = cache.get(key)

        assertNull(retrieved)
        assertEquals(1, cache.size())
    }

    @Test
    fun `cache expiration removes expired entries on get`() {
        val cache = PostHogFeatureFlagCache(maxSize = 10, maxAgeMs = 1) // 1ms TTL
        val key = createTestKey()
        val flags = createTestFlags()

        cache.put(key, flags)
        assertEquals(1, cache.size())

        // Wait for expiration
        Thread.sleep(10)

        val retrieved = cache.get(key)
        assertNull(retrieved)
        assertEquals(0, cache.size())
    }

    @Test
    fun `cache does not expire before TTL`() {
        val cache = PostHogFeatureFlagCache(maxSize = 10, maxAgeMs = 60000) // 1 minute TTL
        val key = createTestKey()
        val flags = createTestFlags()

        cache.put(key, flags)
        val retrieved = cache.get(key)

        assertEquals(flags, retrieved)
        assertEquals(1, cache.size())
    }

    @Test
    fun `LRU eviction removes oldest entry when max size exceeded`() {
        val cache = PostHogFeatureFlagCache(maxSize = 2, maxAgeMs = 60000)
        val key1 = createTestKey("user1")
        val key2 = createTestKey("user2")
        val key3 = createTestKey("user3")
        val flags = createTestFlags()

        // Fill cache to capacity
        cache.put(key1, flags)
        cache.put(key2, flags)
        assertEquals(2, cache.size())

        // Adding third entry should evict first
        cache.put(key3, flags)
        assertEquals(2, cache.size())

        // key1 should be evicted, key2 and key3 should remain
        assertNull(cache.get(key1))
        assertNotNull(cache.get(key2))
        assertNotNull(cache.get(key3))
    }

    @Test
    fun `LRU updates access order on get`() {
        val cache = PostHogFeatureFlagCache(maxSize = 2, maxAgeMs = 60000)
        val key1 = createTestKey("user1")
        val key2 = createTestKey("user2")
        val key3 = createTestKey("user3")
        val flags = createTestFlags()

        // Fill cache
        cache.put(key1, flags)
        cache.put(key2, flags)

        // Access key1 to make it most recently used
        cache.get(key1)

        // Add key3, should evict key2 (least recently used)
        cache.put(key3, flags)

        assertNotNull(cache.get(key1)) // Should still be present
        assertNull(cache.get(key2)) // Should be evicted
        assertNotNull(cache.get(key3)) // Should be present
    }

    @Test
    fun `clear removes all entries`() {
        val cache = PostHogFeatureFlagCache(maxSize = 10, maxAgeMs = 60000)
        val key1 = createTestKey("user1")
        val key2 = createTestKey("user2")
        val flags = createTestFlags()

        cache.put(key1, flags)
        cache.put(key2, flags)
        assertEquals(2, cache.size())

        cache.clear()
        assertEquals(0, cache.size())
        assertNull(cache.get(key1))
        assertNull(cache.get(key2))
    }

    @Test
    fun `size returns correct count`() {
        val cache = PostHogFeatureFlagCache(maxSize = 10, maxAgeMs = 60000)
        assertEquals(0, cache.size())

        val flags = createTestFlags()
        cache.put(createTestKey("user1"), flags)
        assertEquals(1, cache.size())

        cache.put(createTestKey("user2"), flags)
        assertEquals(2, cache.size())

        cache.clear()
        assertEquals(0, cache.size())
    }

    @Test
    fun `getting an expired entry removes it`() {
        val cache = PostHogFeatureFlagCache(maxSize = 10, maxAgeMs = 1) // 1ms TTL
        val flags = createTestFlags()

        for (i in 1..3) {
            cache.put(createTestKey("user$i"), flags)
        }
        assertEquals(3, cache.size())

        // Wait for expiration
        Thread.sleep(10)

        for (i in 1..3) {
            assertEquals(null, cache.get(createTestKey("user$i")))
        }
        assertEquals(0, cache.size())
    }

    @Test
    fun `cache key equality with same values`() {
        val key1 = createTestKey("user1", mapOf("org" to "test"), mapOf("name" to "test"), mapOf("test" to mapOf("size" to "10")))
        val key2 = createTestKey("user1", mapOf("org" to "test"), mapOf("name" to "test"), mapOf("test" to mapOf("size" to "10")))

        val cache = PostHogFeatureFlagCache(maxSize = 10, maxAgeMs = 60000)
        val flags = createTestFlags()

        cache.put(key1, flags)
        val retrieved = cache.get(key2)

        assertEquals(flags, retrieved)
    }

    @Test
    fun `cache key inequality with different values`() {
        val key1 = createTestKey("user1")
        val key2 = createTestKey("user2")

        val cache = PostHogFeatureFlagCache(maxSize = 10, maxAgeMs = 60000)
        val flags = createTestFlags()

        cache.put(key1, flags)
        val retrieved = cache.get(key2)

        assertNull(retrieved)
    }

    @Test
    fun `cache handles null distinctId`() {
        val cache = PostHogFeatureFlagCache(maxSize = 10, maxAgeMs = 60000)
        val key = createTestKey(distinctId = null)
        val flags = createTestFlags()

        cache.put(key, flags)
        val retrieved = cache.get(key)

        assertEquals(flags, retrieved)
    }

    @Test
    fun `cache handles null groups`() {
        val cache = PostHogFeatureFlagCache(maxSize = 10, maxAgeMs = 60000)
        val key = createTestKey(groups = null)
        val flags = createTestFlags()

        cache.put(key, flags)
        val retrieved = cache.get(key)

        assertEquals(flags, retrieved)
    }

    @Test
    fun `cache handles empty maps`() {
        val cache = PostHogFeatureFlagCache(maxSize = 10, maxAgeMs = 60000)
        val key =
            createTestKey(
                groups = emptyMap(),
                personProperties = emptyMap(),
                groupProperties = emptyMap(),
            )
        val flags = createTestFlags()

        cache.put(key, flags)
        val retrieved = cache.get(key)

        assertEquals(flags, retrieved)
    }

    @Test
    fun `cache distinguishes between null and empty maps`() {
        val cache = PostHogFeatureFlagCache(maxSize = 10, maxAgeMs = 60000)
        val keyWithNull = createTestKey(groups = null)
        val keyWithEmpty = createTestKey(groups = emptyMap())
        val flags = createTestFlags()

        cache.put(keyWithNull, flags)
        cache.put(keyWithEmpty, flags)

        assertEquals(flags, cache.get(keyWithNull))
        assertEquals(flags, cache.get(keyWithEmpty))
        assertEquals(2, cache.size())
    }

    @Test
    fun `concurrent access maintains cache consistency`() {
        val cache = PostHogFeatureFlagCache(maxSize = 100, maxAgeMs = 60000)
        val flags = createTestFlags()
        val numThreads = 10
        val numOperationsPerThread = 100

        val threads = mutableListOf<Thread>()

        repeat(numThreads) { threadId ->
            val thread =
                Thread {
                    repeat(numOperationsPerThread) { operation ->
                        val key = createTestKey("user-$threadId-$operation")
                        cache.put(key, flags)
                        cache.get(key)
                    }
                }
            threads.add(thread)
            thread.start()
        }

        threads.forEach { it.join() }

        // Cache should not exceed max size due to LRU eviction
        assertTrue(cache.size() <= 100)
    }

    @Test
    fun `put overwrites existing entry`() {
        val cache = PostHogFeatureFlagCache(maxSize = 10, maxAgeMs = 60000)
        val key = createTestKey()
        val flags1 = createTestFlags()
        val metadata = FeatureFlagMetadata(1, "test-payload", 1)
        val reason = EvaluationReason("condition_match", "Test condition", 0)
        val flags2 = mapOf("different-flag" to FeatureFlag("different-flag", true, null, metadata, reason))

        cache.put(key, flags1)
        cache.put(key, flags2)

        assertEquals(flags2, cache.get(key))
        assertEquals(1, cache.size())
    }

    @Test
    fun `cache with zero max size behaves correctly`() {
        val cache = PostHogFeatureFlagCache(maxSize = 0, maxAgeMs = 60000)
        val key = createTestKey()
        val flags = createTestFlags()

        cache.put(key, flags)
        assertEquals(0, cache.size())
        assertNull(cache.get(key))
    }

    @Test
    fun `cache with zero TTL expires immediately`() {
        val cache = PostHogFeatureFlagCache(maxSize = 10, maxAgeMs = 0)
        val key = createTestKey()
        val flags = createTestFlags()

        cache.put(key, flags)
        // Even without waiting, entry should be expired
        assertNull(cache.get(key))
        assertEquals(0, cache.size())
    }

    private fun createTestKey(distinctId: String?): FeatureFlagCacheKey {
        return FeatureFlagCacheKey(distinctId, null, null, null)
    }
}
