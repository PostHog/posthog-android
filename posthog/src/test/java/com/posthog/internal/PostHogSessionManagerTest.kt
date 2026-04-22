package com.posthog.internal

import java.util.Calendar
import java.util.Date
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogSessionManagerTest {
    @BeforeTest
    internal fun setUp() {
        PostHogSessionManager.isReactNative = false
        PostHogSessionManager.setAppInBackground(false)
        PostHogSessionManager.setOnSessionIdChangedListener(null)
        PostHogSessionManager.setDateProvider(PostHogDeviceDateProvider())
        PostHogSessionManager.endSession()
    }

    @Test
    internal fun `when React Native, startSession does not create new session`() {
        PostHogSessionManager.isReactNative = true
        assertNull(PostHogSessionManager.getActiveSessionId())

        PostHogSessionManager.startSession()

        assertNull(PostHogSessionManager.getActiveSessionId())
        assertFalse(PostHogSessionManager.isSessionActive())
    }

    @Test
    internal fun `when React Native, endSession does not clear existing session`() {
        PostHogSessionManager.isReactNative = true
        val sessionId = UUID.randomUUID()
        PostHogSessionManager.setSessionId(sessionId)

        PostHogSessionManager.endSession()

        assertEquals(sessionId, PostHogSessionManager.getActiveSessionId())
        assertTrue(PostHogSessionManager.isSessionActive())
    }

    @Test
    internal fun `setSessionId works when React Native`() {
        PostHogSessionManager.isReactNative = true
        val sessionId = UUID.randomUUID()

        PostHogSessionManager.setSessionId(sessionId)

        assertEquals(sessionId, PostHogSessionManager.getActiveSessionId())
        assertTrue(PostHogSessionManager.isSessionActive())
    }

    @Test
    internal fun `when not React Native, session rotation works normally`() {
        PostHogSessionManager.isReactNative = false

        PostHogSessionManager.startSession()
        val firstSessionId = PostHogSessionManager.getActiveSessionId()
        assertTrue(PostHogSessionManager.isSessionActive())

        PostHogSessionManager.endSession()
        assertNull(PostHogSessionManager.getActiveSessionId())
        assertFalse(PostHogSessionManager.isSessionActive())

        PostHogSessionManager.startSession()
        val secondSessionId = PostHogSessionManager.getActiveSessionId()
        assertTrue(PostHogSessionManager.isSessionActive())

        assertNotNull(firstSessionId)
        assertNotNull(secondSessionId)
        assertNotEquals(firstSessionId, secondSessionId)
    }

    @Test
    internal fun `startSession sets sessionStartedAt`() {
        PostHogSessionManager.startSession()

        val startedAt = PostHogSessionManager.getSessionStartedAt()
        assertTrue(startedAt > 0L)
    }

    @Test
    internal fun `endSession resets sessionStartedAt to zero`() {
        PostHogSessionManager.startSession()
        assertTrue(PostHogSessionManager.getSessionStartedAt() > 0L)

        PostHogSessionManager.endSession()
        assertEquals(0L, PostHogSessionManager.getSessionStartedAt())
    }

    @Test
    internal fun `getSessionStartedAt returns zero when no session is active`() {
        assertEquals(0L, PostHogSessionManager.getSessionStartedAt())
    }

    @Test
    internal fun `isSessionExceedingMaxDuration returns true after 24 hours`() {
        PostHogSessionManager.startSession()
        val startedAt = PostHogSessionManager.getSessionStartedAt()

        val twentyFourHoursAndOneMinute = startedAt + (1000L * 60 * 60 * 24) + (1000L * 60)
        assertTrue(PostHogSessionManager.isSessionExceedingMaxDuration(twentyFourHoursAndOneMinute))
    }

    @Test
    internal fun `isSessionExceedingMaxDuration returns false before 24 hours`() {
        PostHogSessionManager.startSession()
        val startedAt = PostHogSessionManager.getSessionStartedAt()

        val twentyThreeHours = startedAt + (1000L * 60 * 60 * 23)
        assertFalse(PostHogSessionManager.isSessionExceedingMaxDuration(twentyThreeHours))
    }

    @Test
    internal fun `isSessionExceedingMaxDuration returns false when no session is active`() {
        assertFalse(PostHogSessionManager.isSessionExceedingMaxDuration(System.currentTimeMillis()))
    }

    @Test
    internal fun `setSessionId stamps sessionStartedAt`() {
        val baseTime = 1_000_000_000_000L
        val fakeDate = FakeDateProvider(baseTime)
        PostHogSessionManager.setDateProvider(fakeDate)

        PostHogSessionManager.setSessionId(UUID.randomUUID())

        assertEquals(baseTime, PostHogSessionManager.getSessionStartedAt())
    }

    @Test
    internal fun `getActiveSessionId rotates foregrounded session after 24 hours`() {
        val baseTime = 1_000_000_000_000L
        val fakeDate = FakeDateProvider(baseTime)
        PostHogSessionManager.setDateProvider(fakeDate)

        PostHogSessionManager.startSession()
        val firstSessionId = PostHogSessionManager.getActiveSessionId()
        assertNotNull(firstSessionId)

        // Advance past 24h; app is foregrounded (default)
        fakeDate.nowMs = baseTime + (1000L * 60 * 60 * 24) + 1

        val rotatedSessionId = PostHogSessionManager.getActiveSessionId()
        assertNotNull(rotatedSessionId)
        assertNotEquals(firstSessionId, rotatedSessionId)
        assertTrue(PostHogSessionManager.getSessionStartedAt() > baseTime)
    }

    @Test
    internal fun `getActiveSessionId clears backgrounded session after 24 hours`() {
        val baseTime = 1_000_000_000_000L
        val fakeDate = FakeDateProvider(baseTime)
        PostHogSessionManager.setDateProvider(fakeDate)

        PostHogSessionManager.startSession()
        assertNotNull(PostHogSessionManager.getActiveSessionId())

        PostHogSessionManager.setAppInBackground(true)
        fakeDate.nowMs = baseTime + (1000L * 60 * 60 * 24) + 1

        assertNull(PostHogSessionManager.getActiveSessionId())
        assertEquals(0L, PostHogSessionManager.getSessionStartedAt())
    }

    @Test
    internal fun `getActiveSessionId does not rotate when React Native`() {
        val baseTime = 1_000_000_000_000L
        val fakeDate = FakeDateProvider(baseTime)
        PostHogSessionManager.setDateProvider(fakeDate)

        val rnSessionId = UUID.randomUUID()
        PostHogSessionManager.isReactNative = true
        PostHogSessionManager.setSessionId(rnSessionId)

        fakeDate.nowMs = baseTime + (1000L * 60 * 60 * 48)

        assertEquals(rnSessionId, PostHogSessionManager.getActiveSessionId())
    }

    @Test
    internal fun `getActiveSessionId does not rotate under 24 hours`() {
        val baseTime = 1_000_000_000_000L
        val fakeDate = FakeDateProvider(baseTime)
        PostHogSessionManager.setDateProvider(fakeDate)

        PostHogSessionManager.startSession()
        val firstSessionId = PostHogSessionManager.getActiveSessionId()

        fakeDate.nowMs = baseTime + (1000L * 60 * 60 * 23)

        assertEquals(firstSessionId, PostHogSessionManager.getActiveSessionId())
    }

    @Test
    internal fun `getActiveSessionId fires listener on rotation`() {
        val baseTime = 1_000_000_000_000L
        val fakeDate = FakeDateProvider(baseTime)
        PostHogSessionManager.setDateProvider(fakeDate)

        var callCount = 0
        PostHogSessionManager.setOnSessionIdChangedListener { callCount++ }

        PostHogSessionManager.startSession()
        PostHogSessionManager.getActiveSessionId() // no rotation yet
        assertEquals(0, callCount)

        fakeDate.nowMs = baseTime + (1000L * 60 * 60 * 24) + 1
        PostHogSessionManager.getActiveSessionId() // rotates
        assertEquals(1, callCount)

        // Subsequent reads without further expiry don't re-fire
        PostHogSessionManager.getActiveSessionId()
        assertEquals(1, callCount)
    }

    @Test
    internal fun `getActiveSessionId fires listener on clear`() {
        val baseTime = 1_000_000_000_000L
        val fakeDate = FakeDateProvider(baseTime)
        PostHogSessionManager.setDateProvider(fakeDate)

        var callCount = 0
        PostHogSessionManager.setOnSessionIdChangedListener { callCount++ }

        PostHogSessionManager.startSession()
        PostHogSessionManager.setAppInBackground(true)
        fakeDate.nowMs = baseTime + (1000L * 60 * 60 * 24) + 1

        assertNull(PostHogSessionManager.getActiveSessionId())
        assertEquals(1, callCount)
    }

    @AfterTest
    internal fun cleanup() {
        PostHogSessionManager.isReactNative = false
        PostHogSessionManager.setAppInBackground(false)
        PostHogSessionManager.setOnSessionIdChangedListener(null)
        PostHogSessionManager.setDateProvider(PostHogDeviceDateProvider())
        PostHogSessionManager.endSession()
    }

    private class FakeDateProvider(var nowMs: Long) : PostHogDateProvider {
        override fun currentDate(): Date = Date(nowMs)

        override fun addSecondsToCurrentDate(seconds: Int): Date {
            val cal = Calendar.getInstance()
            cal.timeInMillis = nowMs
            cal.add(Calendar.SECOND, seconds)
            return cal.time
        }

        override fun currentTimeMillis(): Long = nowMs

        override fun nanoTime(): Long = System.nanoTime()
    }
}
