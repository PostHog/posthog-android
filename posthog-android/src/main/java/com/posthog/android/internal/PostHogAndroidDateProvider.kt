package com.posthog.android.internal

import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.posthog.internal.PostHogDateProvider
import java.util.Calendar
import java.util.Date

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class PostHogAndroidDateProvider : PostHogDateProvider {
    private val networkTimeClock =
        runCatching {
            SystemClock.currentNetworkTimeClock()
        }

    override fun currentDate(): Date {
        return Date(currentTimeMillis())
    }

    override fun addSecondsToCurrentDate(seconds: Int): Date {
        return Date(currentTimeMillis() + seconds * 1000)
    }

    override fun currentTimeMillis(): Long {
        val cal = Calendar.getInstance()
        return networkTimeClock
            .mapCatching { it.millis() }
            .getOrDefault(cal.timeInMillis)
    }

    override fun nanoTime(): Long {
        return System.nanoTime()
    }
}
