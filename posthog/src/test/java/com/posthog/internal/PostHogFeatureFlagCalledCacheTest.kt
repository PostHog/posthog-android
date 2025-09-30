package com.posthog.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class PostHogFeatureFlagCalledCacheTest {
    @Test
    fun `add returns true for new entries`() {
        val cache = PostHogFeatureFlagCalledCache(maxSize = 10)

        assertTrue(cache.add("user123", "flag1", "value1"))
        assertEquals(1, cache.size())
    }

    @Test
    fun `add returns false for existing entries`() {
        val cache = PostHogFeatureFlagCalledCache(maxSize = 10)

        assertTrue(cache.add("user123", "flag1", "value1"))
        assertFalse(cache.add("user123", "flag1", "value1"))
        assertFalse(cache.add("user123", "flag1", "value1"))
        assertEquals(1, cache.size())
    }

    @Test
    fun `different users with same flag are tracked separately`() {
        val cache = PostHogFeatureFlagCalledCache(maxSize = 10)

        assertTrue(cache.add("user1", "flag1", "value1"))
        assertTrue(cache.add("user2", "flag1", "value1"))

        assertEquals(2, cache.size())
    }

    @Test
    fun `different flags for same user are tracked separately`() {
        val cache = PostHogFeatureFlagCalledCache(maxSize = 10)

        assertTrue(cache.add("user1", "flag1", "value1"))
        assertTrue(cache.add("user1", "flag2", "value1"))

        assertEquals(2, cache.size())
    }

    @Test
    fun `different values for same user and flag are tracked separately`() {
        val cache = PostHogFeatureFlagCalledCache(maxSize = 10)

        assertTrue(cache.add("user1", "flag1", "value1"))
        assertTrue(cache.add("user1", "flag1", "value2"))

        assertEquals(2, cache.size())
    }

    @Test
    fun `null values are handled correctly`() {
        val cache = PostHogFeatureFlagCalledCache(maxSize = 10)

        assertTrue(cache.add("user1", "flag1", null))
        assertTrue(cache.add("user1", "flag1", "value1"))

        assertEquals(2, cache.size())
    }

    @Test
    fun `add respects max size`() {
        val cache = PostHogFeatureFlagCalledCache(maxSize = 3)

        assertTrue(cache.add("user1", "flag1", "value1"))
        assertTrue(cache.add("user2", "flag1", "value1"))
        assertTrue(cache.add("user3", "flag1", "value1"))

        assertEquals(3, cache.size())

        // Adding 4th entry should evict the oldest
        assertTrue(cache.add("user4", "flag1", "value1"))

        assertEquals(3, cache.size())
    }

    @Test
    fun `LRU eviction removes oldest entry`() {
        val cache = PostHogFeatureFlagCalledCache(maxSize = 3)

        cache.add("user1", "flag1", "value1")
        cache.add("user2", "flag1", "value1")
        cache.add("user3", "flag1", "value1")

        // user1 is the oldest, should be evicted when user4 is added
        cache.add("user4", "flag1", "value1")

        // user1 should now be evicted, so add should return true
        assertTrue(cache.add("user1", "flag1", "value1"))
        assertEquals(3, cache.size())
    }

    @Test
    fun `add updates access order for existing entries`() {
        val cache = PostHogFeatureFlagCalledCache(maxSize = 3)

        cache.add("user1", "flag1", "value1")
        cache.add("user2", "flag1", "value1")
        cache.add("user3", "flag1", "value1")

        // Try to re-add user1 - should return false but update position to most recent
        assertFalse(cache.add("user1", "flag1", "value1"))

        // Add user4, user2 should be evicted (it's now the oldest, since user1 was moved to end)
        cache.add("user4", "flag1", "value1")

        // user2 was evicted, so trying to add it again should return true
        assertTrue(cache.add("user2", "flag1", "value1"))

        // user1 should still be in cache (it was refreshed)
        assertFalse(cache.add("user1", "flag1", "value1"))

        assertEquals(3, cache.size())
    }

    @Test
    fun `clear removes all entries`() {
        val cache = PostHogFeatureFlagCalledCache(maxSize = 10)

        cache.add("user1", "flag1", "value1")
        cache.add("user2", "flag1", "value1")
        cache.add("user3", "flag1", "value1")

        assertEquals(3, cache.size())

        cache.clear()

        assertEquals(0, cache.size())
        // After clear, all entries should be new again
        assertTrue(cache.add("user1", "flag1", "value1"))
        assertTrue(cache.add("user2", "flag1", "value1"))
        assertTrue(cache.add("user3", "flag1", "value1"))
    }

    @Test
    fun `size returns correct count`() {
        val cache = PostHogFeatureFlagCalledCache(maxSize = 10)

        assertEquals(0, cache.size())

        cache.add("user1", "flag1", "value1")
        assertEquals(1, cache.size())

        cache.add("user2", "flag1", "value1")
        assertEquals(2, cache.size())

        cache.add("user3", "flag1", "value1")
        assertEquals(3, cache.size())
    }

    @Test
    fun `cache handles complex scenario with multiple users and flags`() {
        val cache = PostHogFeatureFlagCalledCache(maxSize = 5)

        cache.add("user1", "flag1", "variant_a")
        cache.add("user1", "flag2", "variant_b")
        cache.add("user2", "flag1", "variant_a")
        cache.add("user2", "flag2", "variant_c")
        cache.add("user3", "flag1", null)

        assertEquals(5, cache.size())

        // Add 6th entry, should evict oldest (user1, flag1, variant_a)
        cache.add("user3", "flag2", "variant_d")

        // user1/flag1/variant_a was evicted, so it should be new again
        assertTrue(cache.add("user1", "flag1", "variant_a"))
        assertEquals(5, cache.size())
    }

    @Test
    fun `cache with size 1 works correctly`() {
        val cache = PostHogFeatureFlagCalledCache(maxSize = 1)

        cache.add("user1", "flag1", "value1")
        assertFalse(cache.add("user1", "flag1", "value1"))

        cache.add("user2", "flag1", "value1")
        // user1 was evicted, so it's new again
        assertTrue(cache.add("user1", "flag1", "value1"))
        // user2 was evicted by user1
        assertTrue(cache.add("user2", "flag1", "value1"))
    }

    @Test
    fun `add is atomic for check and add`() {
        val cache = PostHogFeatureFlagCalledCache(maxSize = 10)

        // First call should return true (not seen before)
        val firstResult = cache.add("user123", "flag1", "value1")
        assertTrue(firstResult)

        // Second call should return false (already seen)
        val secondResult = cache.add("user123", "flag1", "value1")
        assertFalse(secondResult)

        // Should only have one entry
        assertEquals(1, cache.size())
    }

    @Test
    fun `batch eviction removes 20 percent when exceeding max size`() {
        val cache = PostHogFeatureFlagCalledCache(maxSize = 10)

        // Fill cache to max capacity
        for (i in 1..10) {
            cache.add("user$i", "flag1", "value1")
        }
        assertEquals(10, cache.size())

        // Adding 11th entry should trigger batch eviction of 20% (2 entries)
        cache.add("user11", "flag1", "value1")

        // Size should be: 10 - 2 (evicted) + 1 (new) = 9
        assertEquals(9, cache.size())

        // The two oldest entries (user1, user2) should have been evicted
        assertTrue(cache.add("user1", "flag1", "value1"))
        assertEquals(10, cache.size())

        // user3 should still be in cache, accessing it moves it to the end
        assertFalse(cache.add("user3", "flag1", "value1"))

        // Now adding user2 should trigger another batch eviction
        // This will evict user4 and user5 (the new oldest after user3 was moved to end)
        assertTrue(cache.add("user2", "flag1", "value1"))
        assertEquals(9, cache.size())

        // user3 should still be in cache (was moved to end, so not evicted)
        assertFalse(cache.add("user3", "flag1", "value1"))

        // user4 and user5 should have been evicted
        assertTrue(cache.add("user4", "flag1", "value1"))
        assertTrue(cache.add("user5", "flag1", "value1"))
    }

    @Test
    fun `batch eviction evicts at least one entry for small caches`() {
        val cache = PostHogFeatureFlagCalledCache(maxSize = 3)

        // Fill to capacity
        cache.add("user1", "flag1", "value1")
        cache.add("user2", "flag1", "value1")
        cache.add("user3", "flag1", "value1")
        assertEquals(3, cache.size())

        // 20% of 3 is 0.6, rounds down to 0, but should evict at least 1
        cache.add("user4", "flag1", "value1")

        // Size should be: 3 - 1 (evicted) + 1 (new) = 3
        assertEquals(3, cache.size())

        // user1 should have been evicted
        assertTrue(cache.add("user1", "flag1", "value1"))
    }

    @Test
    fun `batch eviction allows cache to grow beyond max before next eviction`() {
        val cache = PostHogFeatureFlagCalledCache(maxSize = 10)

        // Fill to capacity
        for (i in 1..10) {
            cache.add("user$i", "flag1", "value1")
        }

        // Trigger first batch eviction by adding 11th entry
        cache.add("user11", "flag1", "value1")
        assertEquals(9, cache.size()) // 10 - 2 + 1

        // Can now add one more entry without triggering eviction
        cache.add("user12", "flag1", "value1")
        assertEquals(10, cache.size())

        // Adding another should trigger next batch eviction
        cache.add("user13", "flag1", "value1")
        assertEquals(9, cache.size()) // 10 - 2 + 1
    }
}
