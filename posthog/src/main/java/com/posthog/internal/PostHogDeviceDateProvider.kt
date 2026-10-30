package com.posthog.internal

import com.posthog.PostHogInternal
import java.util.Date

/**
 * The implementation of the PostHogDateProvider interface that reads the current time
 *  and more based on the device's clock.
 */
@PostHogInternal
public class PostHogDeviceDateProvider : PostHogDateProvider {
    override fun currentDate(): Date {
        return Date()
    }

    override fun addSecondsToCurrentDate(seconds: Int): Date {
        return Date(System.currentTimeMillis() + seconds * 1000L)
    }

    override fun currentTimeMillis(): Long {
        return System.currentTimeMillis()
    }

    override fun nanoTime(): Long {
        return System.nanoTime()
    }
}
