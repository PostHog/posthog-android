package com.posthog.android.internal

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34]) // PostHogAndroidDateProvider requires API >= TIRAMISU.
internal class PostHogAndroidDateProviderTest {
    private class CountingClock(private val now: Long) : Clock() {
        val millisCalls = AtomicInteger(0)

        override fun millis(): Long {
            millisCalls.incrementAndGet()
            return now
        }

        override fun instant(): Instant = Instant.ofEpochMilli(now)

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this
    }

    @Test
    fun `does not query the network clock on every call`() {
        val clock = CountingClock(1_000_000L)
        val sut = PostHogAndroidDateProvider(networkClock = clock, elapsedRealtimeMs = { 100L }, refreshIntervalMs = 60_000L)

        repeat(50) { sut.currentTimeMillis() }

        assertEquals(1, clock.millisCalls.get())
    }

    @Test
    fun `derives time from the elapsed delta between refreshes`() {
        val clock = CountingClock(1_000_000L)
        var elapsed = 100L
        val sut = PostHogAndroidDateProvider(networkClock = clock, elapsedRealtimeMs = { elapsed }, refreshIntervalMs = 60_000L)

        assertEquals(1_000_000L, sut.currentTimeMillis())
        elapsed = 5_100L
        assertEquals(1_005_000L, sut.currentTimeMillis())
        assertEquals(1, clock.millisCalls.get())
    }

    @Test
    fun `refreshes the anchor after the interval elapses`() {
        val clock = CountingClock(1_000_000L)
        var elapsed = 100L
        val sut = PostHogAndroidDateProvider(networkClock = clock, elapsedRealtimeMs = { elapsed }, refreshIntervalMs = 60_000L)

        sut.currentTimeMillis()
        elapsed = 100L + 60_000L
        sut.currentTimeMillis()

        assertEquals(2, clock.millisCalls.get())
    }
}
