package com.posthog.internal

import java.util.Date

/**
 * Interface to read the current Date
 */
internal interface PostHogDateProvider {
    fun currentDate(): Date

    fun addSecondsToCurrentDate(seconds: Int): Date
}
