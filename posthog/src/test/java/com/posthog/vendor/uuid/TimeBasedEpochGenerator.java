// borrowed and adapted from https://github.com/cowtowncoder/java-uuid-generator/blob/master/src/main/java/com/fasterxml/uuid/impl/TimeBasedEpochGenerator.java

package com.posthog.vendor.uuid;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Implementation of UUID generator that uses time/location based generation
 * method field from the Unix Epoch timestamp source - the number of
 * milliseconds seconds since midnight 1 Jan 1970 UTC, leap seconds excluded.
 * This is usually referred to as "Version 7".
 * <p>
 * As all JUG provided implementations, this generator is fully thread-safe.
 *
 * @since 4.1
 */
public class TimeBasedEpochGenerator
{
    private static final TimeBasedEpochGenerator INSTANCE = new TimeBasedEpochGenerator();

    private TimeBasedEpochGenerator() {
    }

    public static TimeBasedEpochGenerator getInstance() {
        return INSTANCE;
    }

    private static final int ENTROPY_BYTE_LENGTH = 10;

    private static final int TIME_BASED_EPOCH_RAW = 7;

    /*
    /**********************************************************************
    /* Configuration
    /**********************************************************************
     */

    private long _lastTimestamp = -1;
    private final byte[] _lastEntropy  = new byte[ENTROPY_BYTE_LENGTH];

    private final SecureRandom numberGenerator = new SecureRandom();
    private final Lock lock = new ReentrantLock();

    /*
    /**********************************************************************
    /* Construction
    /**********************************************************************
     */

    /*
    /**********************************************************************
    /* Access to config
    /**********************************************************************
     */

    /*
    /**********************************************************************
    /* UUID generation
    /**********************************************************************
     */

    /**
     * @return unix epoch time based UUID
     */
    public UUID generate()
    {
        return generate(System.currentTimeMillis());
    }

    /**
     * @param rawTimestamp unix epoch millis
     * @return unix epoch time based UUID
     */
    public UUID generate(long rawTimestamp) throws IllegalStateException
    {
        return construct(rawTimestamp);
    }

    private long _toLong(byte[] buffer, int offset)
    {
        long l1 = _toInt(buffer, offset);
        long l2 = _toInt(buffer, offset+4);
        long l = (l1 << 32) + ((l2 << 32) >>> 32);
        return l;
    }

    private long _toInt(byte[] buffer, int offset)
    {
        return (buffer[offset] << 24)
                + ((buffer[++offset] & 0xFF) << 16)
                + ((buffer[++offset] & 0xFF) << 8)
                + (buffer[++offset] & 0xFF);
    }

    private long _toShort(byte[] buffer, int offset)
    {
        return ((buffer[offset] & 0xFF) << 8)
                + (buffer[++offset] & 0xFF);
    }

    private static UUID constructUUID(long l1, long l2)
    {
        // first, ensure type is ok
        l1 &= ~0xF000L; // remove high nibble of 6th byte
        l1 |= (long) (TIME_BASED_EPOCH_RAW << 12);
        // second, ensure variant is properly set too (8th byte; most-sig byte of second long)
        l2 = ((l2 << 2) >>> 2); // remove 2 MSB
        l2 |= (2L << 62); // set 2 MSB to '10'
        return new UUID(l1, l2);
    }

    /*
    /********************************************************************************
    /* Package helper methods
    /********************************************************************************
     */

    /**
     * Method that will construct actual {@link UUID} instance for given
     * unix epoch timestamp: called by {@link #generate()} but may alternatively be
     * called directly to construct an instance with known timestamp.
     * NOTE: calling this method directly produces somewhat distinct UUIDs as
     * "entropy" value is still generated as necessary to avoid producing same
     * {@link UUID} even if same timestamp is being passed.
     *
     * @param rawTimestamp unix epoch millis
     *
     * @return unix epoch time based UUID
     *
     * @since 4.3
     */
    private UUID construct(long rawTimestamp) throws IllegalStateException
    {
        lock.lock();
        try {
            if (rawTimestamp == _lastTimestamp) {
                boolean c = true;
                for (int i = ENTROPY_BYTE_LENGTH - 1; i >= 0; i--) {
                    if (c) {
                        byte temp = _lastEntropy[i];
                        temp = (byte) (temp + 0x01);
                        c = _lastEntropy[i] == (byte) 0xff;
                        _lastEntropy[i] = temp;
                    }
                }
                if (c) {
                    throw new IllegalStateException("overflow on same millisecond");
                }
            } else {
                _lastTimestamp = rawTimestamp;
                numberGenerator.nextBytes(_lastEntropy);
            }
            return constructUUID((rawTimestamp << 16) | _toShort(_lastEntropy, 0), _toLong(_lastEntropy, 2));
        } finally {
            lock.unlock();
        }
    }
}
