package com.posthog.android.replay.internal

/**
 * Minimal open-addressing set of int keys (view identity hash codes), so the per-frame
 * mask walks don't box an Integer plus a map entry per visited view.
 */
internal class IntHashSet {
    // 0 marks an empty slot, so a real 0 key is tracked separately.
    private var slots = IntArray(INITIAL_CAPACITY)
    private var size = 0
    private var containsZero = false

    fun clear() {
        slots.fill(0)
        size = 0
        containsZero = false
    }

    // Returns true when the value was not in the set yet.
    fun add(value: Int): Boolean {
        if (value == 0) {
            val added = !containsZero
            containsZero = true
            return added
        }
        if ((size + 1) * 4 > slots.size * 3) {
            grow()
        }
        val added = insert(slots, value)
        if (added) {
            size++
        }
        return added
    }

    private fun grow() {
        val old = slots
        slots = IntArray(old.size * 2)
        for (value in old) {
            if (value != 0) {
                insert(slots, value)
            }
        }
    }

    private fun insert(
        table: IntArray,
        value: Int,
    ): Boolean {
        val mask = table.size - 1
        val hash = value * HASH_MULTIPLIER
        var index = (hash xor (hash ushr 16)) and mask
        while (true) {
            val existing = table[index]
            if (existing == 0) {
                table[index] = value
                return true
            }
            if (existing == value) {
                return false
            }
            index = (index + 1) and mask
        }
    }

    private companion object {
        // Must stay a power of two: insert() relies on size-1 as a bit mask.
        private const val INITIAL_CAPACITY = 128

        // 2^32 / golden ratio (Fibonacci hashing) spreads sequential identity hashes.
        private const val HASH_MULTIPLIER = -0x61c88647
    }
}
