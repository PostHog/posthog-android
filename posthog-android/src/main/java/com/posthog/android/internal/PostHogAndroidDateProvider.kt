package com.posthog.android.internal

import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.posthog.internal.PostHogDateProvider
import java.time.Instant
import java.util.Date

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class PostHogAndroidDateProvider : PostHogDateProvider {
    private val networkTimeClock =
        runCatching {
            SystemClock.currentNetworkTimeClock()
        }

    private val instant
        get() =
            networkTimeClock
                .mapCatching { it.instant() }
                .getOrDefault(Instant.now())

    override fun currentDate(): Date {
        return Date.from(instant)
    }

    override fun addSecondsToCurrentDate(seconds: Int): Date {
        return Date.from(instant.plusSeconds(seconds.toLong()))
    }

    override fun currentTimeMillis(): Long {
        return instant.toEpochMilli()
    }

    override fun nanoTime(): Long {
        return System.nanoTime()
    }
}
