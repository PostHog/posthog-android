// borrowed, adapted and converted to Kotlin from https://github.com/cowtowncoder/java-uuid-generator/blob/master/src/main/java/com/fasterxml/uuid/impl/TimeBasedEpochGenerator.java

package com.posthog.vendor.uuid

import java.security.SecureRandom
import java.util.Random
import java.util.UUID
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock

internal object TimeBasedEpochGenerator {
    private const val ENTROPY_BYTE_LENGTH = 10

    private const val TIME_BASED_EPOCH_RAW = 7

    /*
    / **********************************************************************
    / * Configuration
    / **********************************************************************
     */
    private var lastTimestamp: Long = -1
    private val lastEntropy = ByteArray(ENTROPY_BYTE_LENGTH)

    private val numberGenerator: Random = SecureRandom()
    private val lock: Lock = ReentrantLock()

    /*
    / **********************************************************************
    / * UUID generation
    / **********************************************************************
     */

    /**
     * @return unix epoch time based UUID
     */
    fun generate(): UUID {
        return generate(System.currentTimeMillis())
    }

    /**
     * @param rawTimestamp unix epoch millis
     * @return unix epoch time based UUID
     */
    @Throws(IllegalStateException::class)
    fun generate(rawTimestamp: Long): UUID {
        return construct(rawTimestamp)
    }

    private fun toLong(
        buffer: ByteArray,
        offset: Int,
    ): Long {
        val l1 = toInt(buffer, offset)
        val l2 = toInt(buffer, offset + 4)
        val l = (l1 shl 32) + ((l2 shl 32) ushr 32)
        return l
    }

    private fun toInt(
        buffer: ByteArray,
        offset: Int,
    ): Long {
        var theOffset = offset
        return (
            (buffer[theOffset].toInt() shl 24) +
                ((buffer[++theOffset].toInt() and 0xFF) shl 16) +
                ((buffer[++theOffset].toInt() and 0xFF) shl 8) +
                (buffer[++theOffset].toInt() and 0xFF)
        ).toLong()
    }

    private fun toShort(
        buffer: ByteArray,
        offset: Int,
    ): Long {
        var theOffset = offset
        return (
            ((buffer[theOffset].toInt() and 0xFF) shl 8) +
                (buffer[++theOffset].toInt() and 0xFF)
        ).toLong()
    }

    private fun constructUUID(
        l1: Long,
        l2: Long,
    ): UUID {
        // first, ensure type is ok
        var theL1 = l1
        var theL2 = l2
        theL1 = theL1 and 0xF000L.inv() // remove high nibble of 6th byte
        theL1 = theL1 or (TIME_BASED_EPOCH_RAW shl 12).toLong()
        // second, ensure variant is properly set too (8th byte; most-sig byte of second long)
        theL2 = ((theL2 shl 2) ushr 2) // remove 2 MSB
        theL2 = theL2 or (2L shl 62) // set 2 MSB to '10'
        return UUID(theL1, theL2)
    }

    /*
    / ********************************************************************************
    / * Package helper methods
    / ********************************************************************************
     */

    /**
     * Method that will construct actual [UUID] instance for given
     * unix epoch timestamp: called by [.generate] but may alternatively be
     * called directly to construct an instance with known timestamp.
     * NOTE: calling this method directly produces somewhat distinct UUIDs as
     * "entropy" value is still generated as necessary to avoid producing same
     * [UUID] even if same timestamp is being passed.
     *
     * @param rawTimestamp unix epoch millis
     *
     * @return unix epoch time based UUID
     *
     * @since 4.3
     */
    @Throws(IllegalStateException::class)
    private fun construct(rawTimestamp: Long): UUID {
        lock.lock()
        try {
            if (rawTimestamp == lastTimestamp) {
                var c = true
                for (i in ENTROPY_BYTE_LENGTH - 1 downTo 0) {
                    if (c) {
                        var temp = lastEntropy[i]
                        temp = (temp + 0x01).toByte()
                        c = lastEntropy[i] == 0xff.toByte()
                        lastEntropy[i] = temp
                    }
                }
                check(!c) { "overflow on same millisecond" }
            } else {
                lastTimestamp = rawTimestamp
                numberGenerator.nextBytes(lastEntropy)
            }
            return constructUUID(
                (rawTimestamp shl 16) or toShort(lastEntropy, 0),
                toLong(lastEntropy, 2),
            )
        } finally {
            lock.unlock()
        }
    }
}
