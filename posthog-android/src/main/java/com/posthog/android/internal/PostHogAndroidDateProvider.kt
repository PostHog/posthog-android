package com.posthog.android.internal

import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.posthog.internal.PostHogDateProvider
import java.time.Clock
import java.util.Date

/**
 * Provides the current time, corrected to network time when available.
 *
 * [Clock.millis] on the clock returned by [SystemClock.currentNetworkTimeClock] performs a Binder
 * IPC to a system service on every call, so querying it on each timestamp (e.g. on every touch
 * event) blocks the calling thread and can ANR under load. Instead the network time is sampled at
 * most once per [refreshIntervalMs] and subsequent timestamps are derived from the monotonic
 * [android.os.SystemClock.elapsedRealtime] delta since that anchor.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class PostHogAndroidDateProvider(
    private val networkClock: Clock? =
        runCatching { SystemClock.currentNetworkTimeClock() }.getOrNull(),
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val refreshIntervalMs: Long = REFRESH_INTERVAL_MS,
) : PostHogDateProvider {
    @Volatile
    private var anchorNetworkMs = 0L

    @Volatile
    private var anchorElapsedMs = 0L

    @Volatile
    private var hasAnchor = false

    override fun currentDate(): Date {
        return Date(currentTimeMillis())
    }

    override fun addSecondsToCurrentDate(seconds: Int): Date {
        return Date(currentTimeMillis() + seconds * 1000)
    }

    override fun currentTimeMillis(): Long {
        val clock = networkClock ?: return System.currentTimeMillis()
        val elapsed = elapsedRealtimeMs()
        if (!hasAnchor || elapsed - anchorElapsedMs >= refreshIntervalMs) {
            val networkNow = runCatching { clock.millis() }.getOrNull()
            if (networkNow != null) {
                anchorNetworkMs = networkNow
                anchorElapsedMs = elapsed
                hasAnchor = true
                return networkNow
            }
        }
        return if (hasAnchor) anchorNetworkMs + (elapsed - anchorElapsedMs) else System.currentTimeMillis()
    }

    override fun nanoTime(): Long {
        return System.nanoTime()
    }

    private companion object {
        private const val REFRESH_INTERVAL_MS = 60_000L
    }
}
