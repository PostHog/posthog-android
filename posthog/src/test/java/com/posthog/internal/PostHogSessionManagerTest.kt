package com.posthog.internal

import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogSessionManagerTest {
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
    internal fun `rotateSession creates a new session with a new id`() {
        PostHogSessionManager.startSession()
        val firstSessionId = PostHogSessionManager.getActiveSessionId()
        assertNotNull(firstSessionId)

        PostHogSessionManager.rotateSession()
        val secondSessionId = PostHogSessionManager.getActiveSessionId()
        assertNotNull(secondSessionId)

        assertNotEquals(firstSessionId, secondSessionId)
        assertTrue(PostHogSessionManager.isSessionActive())
    }

    @Test
    internal fun `rotateSession updates sessionStartedAt`() {
        PostHogSessionManager.startSession()
        val firstStartedAt = PostHogSessionManager.getSessionStartedAt()
        assertTrue(firstStartedAt > 0L)

        // Small delay to ensure different timestamp
        Thread.sleep(10)

        PostHogSessionManager.rotateSession()
        val secondStartedAt = PostHogSessionManager.getSessionStartedAt()
        assertTrue(secondStartedAt >= firstStartedAt)
    }

    @Test
    internal fun `when React Native, rotateSession does not rotate session`() {
        PostHogSessionManager.isReactNative = true
        val sessionId = UUID.randomUUID()
        PostHogSessionManager.setSessionId(sessionId)

        PostHogSessionManager.rotateSession()

        assertEquals(sessionId, PostHogSessionManager.getActiveSessionId())
    }

    @AfterTest
    internal fun cleanup() {
        PostHogSessionManager.isReactNative = false
        PostHogSessionManager.dateProvider = PostHogDeviceDateProvider()
        PostHogSessionManager.endSession()
    }
}
