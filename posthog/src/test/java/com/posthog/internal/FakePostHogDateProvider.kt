package com.posthog.internal

import java.util.Calendar
import java.util.Date

internal class FakePostHogDateProvider : PostHogDateProvider {
    private var currentDate: Date? = null
    private var addSecondsToCurrentDate: Date? = null

    fun setCurrentDate(date: Date) {
        currentDate = date
    }

    override fun currentDate(): Date {
        return currentDate ?: Date()
    }

    fun setAddSecondsToCurrentDate(date: Date) {
        addSecondsToCurrentDate = date
    }

    override fun addSecondsToCurrentDate(seconds: Int): Date {
        if (addSecondsToCurrentDate != null) {
            return addSecondsToCurrentDate!!
        }
        val cal = Calendar.getInstance()
        cal.add(Calendar.SECOND, seconds)
        return cal.time
    }

    override fun currentTimeMillis(): Long {
        return System.currentTimeMillis()
    }

    override fun nanoTime(): Long {
        return System.nanoTime()
    }
}
