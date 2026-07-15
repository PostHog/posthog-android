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
 *
 * Once a sample has succeeded, later sampling failures extend the existing anchor rather than
 * falling back to the system wall clock: the platform's network clock derives time the same way,
 * and the anchor stays immune to user changes of the device clock.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class PostHogAndroidDateProvider(
    private val networkClock: Clock? =
        runCatching { SystemClock.currentNetworkTimeClock() }.getOrNull(),
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val refreshIntervalMs: Long = REFRESH_INTERVAL_MS,
) : PostHogDateProvider {
    // A network-time sample and the elapsedRealtime at which it was taken, published together as a
    // single immutable value so readers never observe a mixed (torn) anchor.
    private class Anchor(val networkMs: Long, val elapsedMs: Long)

    @Volatile
    private var anchor: Anchor? = null

    // elapsedRealtime of the last IPC attempt (success or failure), throttling attempts to at most
    // one per refreshIntervalMs even when network time is unavailable, so a failing clock does not
    // re-run the Binder IPC on every call.
    @Volatile
    private var lastAttemptElapsedMs: Long? = null

    override fun currentDate(): Date {
        return Date(currentTimeMillis())
    }

    override fun addSecondsToCurrentDate(seconds: Int): Date {
        return Date(currentTimeMillis() + seconds * 1000)
    }

    override fun currentTimeMillis(): Long {
        val clock = networkClock ?: return System.currentTimeMillis()
        val elapsed = elapsedRealtimeMs()
        val current = anchor
        val anchorStale = current == null || elapsed - current.elapsedMs >= refreshIntervalMs
        val lastAttempt = lastAttemptElapsedMs
        val mayAttempt = lastAttempt == null || elapsed - lastAttempt >= refreshIntervalMs
        if (anchorStale && mayAttempt) {
            lastAttemptElapsedMs = elapsed
            val networkNow = runCatching { clock.millis() }.getOrNull()
            if (networkNow != null) {
                // re-read the monotonic clock: the sample corresponds to the moment millis()
                // returned, and the IPC itself may have blocked long enough to skew the anchor
                anchor = Anchor(networkNow, elapsedRealtimeMs())
                return networkNow
            }
        }
        val latest = anchor
        return if (latest != null) latest.networkMs + (elapsed - latest.elapsedMs) else System.currentTimeMillis()
    }

    override fun nanoTime(): Long {
        return System.nanoTime()
    }

    private companion object {
        private const val REFRESH_INTERVAL_MS = 60_000L
    }
}
