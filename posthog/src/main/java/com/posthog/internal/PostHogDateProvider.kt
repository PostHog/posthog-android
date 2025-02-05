package com.posthog.internal

import com.posthog.PostHogInternal
import java.util.Date

/**
 * Interface to read the current Date
 */
@PostHogInternal
public interface PostHogDateProvider {
    public fun currentDate(): Date

    public fun addSecondsToCurrentDate(seconds: Int): Date

    public fun currentTimeMillis(): Long

    public fun nanoTime(): Long
}
