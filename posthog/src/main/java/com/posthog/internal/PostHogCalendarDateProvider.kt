package com.posthog.internal

import java.util.Calendar
import java.util.Date

/**
 * The implementation of the PostHogDateProvider interface that reads the current time
 * based on the device's clock.
 */
internal class PostHogCalendarDateProvider : PostHogDateProvider {
    override fun currentDate(): Date {
        val cal = Calendar.getInstance()
        return cal.time
    }

    override fun addSecondsToCurrentDate(seconds: Int): Date {
        val cal = Calendar.getInstance()
        cal.add(Calendar.SECOND, seconds)
        return cal.time
    }
}
