package com.posthog.vendor.uuid

import java.util.UUID

/**
 * Default [java.util.UUID] comparator is not very useful, since
 * it just does blind byte-by-byte comparison which does not work well
 * for time+location - based UUIDs. Additionally, it also uses signed
 * comparisons for longs which can lead to unexpected behavior
 * This comparator does implement proper lexical ordering: starting with
 * type (different types are collated
 * separately), followed by time and location (for time/location based),
 * and simple lexical (byte-by-byte) ordering for name/hash and random
 * versions.
 *
 * @author tatu
 */
public class UUIDComparator : Comparator<UUID> {
    public override fun compare(
        u1: UUID,
        u2: UUID,
    ): Int {
        return staticCompare(u1, u2)
    }

    private fun staticCompare(
        u1: UUID,
        u2: UUID,
    ): Int {
        // First: major sorting by types
        val type = u1.version()
        var diff = type - u2.version()
        if (diff != 0) {
            return diff
        }
        // Second: for time-based version, order by time stamp:
        // 1 = TIME_BASED
        if (type == 1) {
            diff = compareULongs(u1.timestamp(), u2.timestamp())
            if (diff == 0) {
                // or if that won't work, by other bits lexically
                diff = compareULongs(u1.leastSignificantBits, u2.leastSignificantBits)
            }
        } else {
            // note: java.util.UUIDs compares with sign extension, IMO that's wrong, so:
            diff =
                compareULongs(
                    u1.mostSignificantBits,
                    u2.mostSignificantBits,
                )
            if (diff == 0) {
                diff =
                    compareULongs(
                        u1.leastSignificantBits,
                        u2.leastSignificantBits,
                    )
            }
        }
        return diff
    }

    private fun compareULongs(
        l1: Long,
        l2: Long,
    ): Int {
        var diff = compareUInts((l1 shr 32).toInt(), (l2 shr 32).toInt())
        if (diff == 0) {
            diff = compareUInts(l1.toInt(), l2.toInt())
        }
        return diff
    }

    private fun compareUInts(
        i1: Int,
        i2: Int,
    ): Int {
        /* bit messier due to java's insistence on signed values: if both
         * have same sign, normal comparison (by subtraction) works fine;
         * but if signs don't agree need to resolve differently
         */
        if (i1 < 0) {
            return if ((i2 < 0)) (i1 - i2) else 1
        }
        return if ((i2 < 0)) -1 else (i1 - i2)
    }
}
