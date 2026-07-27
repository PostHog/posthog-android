package com.posthog.android.internal

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34]) // PostHogAndroidDateProvider requires API >= TIRAMISU.
internal class PostHogAndroidDateProviderTest {
    private val directExecutor = Executor { it.run() }

    private class QueuedExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        val pendingCount: Int
            get() = tasks.size

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runNext() {
            tasks.removeFirst().run()
        }
    }

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
    fun `queries the network clock only through the refresh executor`() {
        val clock = CountingClock(1_000_000L)
        val executor = QueuedExecutor()
        val sut =
            PostHogAndroidDateProvider(
                networkClock = clock,
                elapsedRealtimeMs = { 100L },
                refreshIntervalMs = 60_000L,
                refreshExecutor = executor,
            )

        repeat(50) { sut.currentTimeMillis() }

        assertEquals(0, clock.millisCalls.get())
        assertEquals(1, executor.pendingCount)

        executor.runNext()

        assertEquals(1_000_000L, sut.currentTimeMillis())
        assertEquals(1, clock.millisCalls.get())
    }

    @Test
    fun `concurrent callers schedule only one network refresh`() {
        val clock = CountingClock(1_000_000L)
        val executor = QueuedExecutor()
        val sut =
            PostHogAndroidDateProvider(
                networkClock = clock,
                elapsedRealtimeMs = { 100L },
                refreshIntervalMs = 60_000L,
                refreshExecutor = executor,
            )
        val start = CountDownLatch(1)
        val callers =
            List(20) {
                Thread {
                    start.await()
                    sut.currentTimeMillis()
                }.apply { start() }
            }

        start.countDown()
        callers.forEach(Thread::join)

        assertEquals(0, clock.millisCalls.get())
        assertEquals(1, executor.pendingCount)
    }

    @Test
    fun `does not query the network clock on every call`() {
        val clock = CountingClock(1_000_000L)
        val sut =
            PostHogAndroidDateProvider(
                networkClock = clock,
                elapsedRealtimeMs = { 100L },
                refreshIntervalMs = 60_000L,
                refreshExecutor = directExecutor,
            )

        repeat(50) { sut.currentTimeMillis() }

        assertEquals(1, clock.millisCalls.get())
    }

    @Test
    fun `derives time from the elapsed delta between refreshes`() {
        val clock = CountingClock(1_000_000L)
        var elapsed = 100L
        val sut =
            PostHogAndroidDateProvider(
                networkClock = clock,
                elapsedRealtimeMs = { elapsed },
                refreshIntervalMs = 60_000L,
                refreshExecutor = directExecutor,
            )

        assertEquals(1_000_000L, sut.currentTimeMillis())
        elapsed = 5_100L
        assertEquals(1_005_000L, sut.currentTimeMillis())
        assertEquals(1, clock.millisCalls.get())
    }

    private class ThrowingClock : Clock() {
        val millisCalls = AtomicInteger(0)

        override fun millis(): Long {
            millisCalls.incrementAndGet()
            throw java.time.DateTimeException("network time unavailable")
        }

        override fun instant(): Instant = throw java.time.DateTimeException("network time unavailable")

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this
    }

    @Test
    fun `does not retry the network clock on every call when sampling fails`() {
        val clock = ThrowingClock()
        val sut =
            PostHogAndroidDateProvider(
                networkClock = clock,
                elapsedRealtimeMs = { 100L },
                refreshIntervalMs = 60_000L,
                refreshExecutor = directExecutor,
            )

        repeat(50) { sut.currentTimeMillis() }

        assertEquals(1, clock.millisCalls.get())
    }

    @Test
    fun `refreshes the anchor after the interval elapses`() {
        val clock = CountingClock(1_000_000L)
        var elapsed = 100L
        val sut =
            PostHogAndroidDateProvider(
                networkClock = clock,
                elapsedRealtimeMs = { elapsed },
                refreshIntervalMs = 60_000L,
                refreshExecutor = directExecutor,
            )

        sut.currentTimeMillis()
        elapsed = 100L + 60_000L
        sut.currentTimeMillis()

        assertEquals(2, clock.millisCalls.get())
    }

    @Test
    fun `does not bake a slow sample's own latency into the anchor`() {
        var elapsed = 100L
        // network time is defined as base + elapsedRealtime; millis() blocks for 5s before returning
        val clock =
            object : Clock() {
                override fun millis(): Long {
                    elapsed += 5_000L
                    return 1_000_000L + elapsed
                }

                override fun instant(): Instant = Instant.ofEpochMilli(millis())

                override fun getZone(): ZoneId = ZoneOffset.UTC

                override fun withZone(zone: ZoneId?): Clock = this
            }
        val sut =
            PostHogAndroidDateProvider(
                networkClock = clock,
                elapsedRealtimeMs = { elapsed },
                refreshIntervalMs = 60_000L,
                refreshExecutor = directExecutor,
            )

        sut.currentTimeMillis()
        elapsed += 1_000L

        assertEquals(1_000_000L + elapsed, sut.currentTimeMillis())
    }

    @Test
    fun `retries a failed network sample after the interval elapses`() {
        val clock = ThrowingClock()
        var elapsed = 100L
        val sut =
            PostHogAndroidDateProvider(
                networkClock = clock,
                elapsedRealtimeMs = { elapsed },
                refreshIntervalMs = 60_000L,
                refreshExecutor = directExecutor,
            )

        sut.currentTimeMillis()
        elapsed += 60_000L
        sut.currentTimeMillis()

        assertEquals(2, clock.millisCalls.get())
    }

    private class SucceedOnceClock(private val now: Long) : Clock() {
        val millisCalls = AtomicInteger(0)

        override fun millis(): Long {
            if (millisCalls.incrementAndGet() > 1) {
                throw java.time.DateTimeException("network time unavailable")
            }
            return now
        }

        override fun instant(): Instant = Instant.ofEpochMilli(now)

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this
    }

    @Test
    fun `a failed refresh extends the existing anchor instead of falling back to system time`() {
        val clock = SucceedOnceClock(1_000_000L)
        var elapsed = 100L
        val sut =
            PostHogAndroidDateProvider(
                networkClock = clock,
                elapsedRealtimeMs = { elapsed },
                refreshIntervalMs = 60_000L,
                refreshExecutor = directExecutor,
            )

        assertEquals(1_000_000L, sut.currentTimeMillis())
        elapsed += 65_000L

        assertEquals(1_065_000L, sut.currentTimeMillis())
        assertEquals(2, clock.millisCalls.get())
    }
}
