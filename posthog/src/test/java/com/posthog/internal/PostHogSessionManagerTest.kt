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

    @AfterTest
    internal fun cleanup() {
        PostHogSessionManager.isReactNative = false
        PostHogSessionManager.endSession()
    }
}
