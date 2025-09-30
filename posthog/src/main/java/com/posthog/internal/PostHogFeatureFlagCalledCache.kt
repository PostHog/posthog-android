package com.posthog.internal

/**
 * LRU cache for tracking which feature flag values have been seen
 * to deduplicate $feature_flag_called events
 */
internal class PostHogFeatureFlagCalledCache(
    private val maxSize: Int,
) {
    // LinkedHashMap isn't supported in Android API 21. We use a linked list instead
    // to maintain the order of access for LRU eviction
    private class Node(
        val key: FeatureFlagCalledKey,
        var prev: Node? = null,
        var next: Node? = null,
    )

    private val cache = HashMap<FeatureFlagCalledKey, Node>()
    private var head: Node? = null // Most recently used
    private var tail: Node? = null // Least recently used

    /**
     * Atomically check if this combination has been seen before, and if not, mark it as seen.
     * Returns true if this is the first time seeing this combination (was added), false if already seen.
     */
    @Synchronized
    fun add(
        distinctId: String,
        flagKey: String,
        value: Any?,
    ): Boolean {
        val key = FeatureFlagCalledKey(distinctId, flagKey, value)

        val existingNode = cache.get(key)
        if (existingNode != null) {
            // Mark as most recent
            moveToHead(existingNode)
            return false
        }

        val newNode = Node(key)
        cache.put(key, newNode)
        addToHead(newNode)

        // When over max size, evict some percentage of the oldest entries
        if (cache.size > maxSize) {
            val evictionCount = (maxSize * BATCH_EVICTION_FACTOR).toInt().coerceAtLeast(1)
            repeat(evictionCount) {
                removeTail()
            }
        }

        return true
    }

    private fun addToHead(node: Node) {
        node.next = head
        node.prev = null
        head?.prev = node
        head = node
        if (tail == null) {
            tail = node
        }
    }

    private fun removeNode(node: Node) {
        val prev = node.prev
        val next = node.next

        if (prev != null) {
            prev.next = next
        } else {
            head = next
        }

        if (next != null) {
            next.prev = prev
        } else {
            tail = prev
        }
    }

    private fun moveToHead(node: Node) {
        if (node == head) return
        removeNode(node)
        addToHead(node)
    }

    private fun removeTail() {
        val tailNode = tail ?: return
        cache.remove(tailNode.key)
        val prev = tailNode.prev
        if (prev != null) {
            prev.next = null
            tail = prev
        } else {
            head = null
            tail = null
        }
    }

    /**
     * Clear all cached entries
     */
    @Synchronized
    fun clear() {
        cache.clear()
        head = null
        tail = null
    }

    /**
     * Get current cache size
     */
    @Synchronized
    fun size(): Int = cache.size

    private companion object {
        const val BATCH_EVICTION_FACTOR = 0.2
    }
}

/**
 * Cache key combining distinct ID, flag key, and value
 */
internal data class FeatureFlagCalledKey(
    val distinctId: String,
    val flagKey: String,
    val value: Any?,
)
