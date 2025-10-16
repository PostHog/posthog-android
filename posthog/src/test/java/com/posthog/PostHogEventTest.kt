package com.posthog

import com.posthog.internal.errortracking.ThrowableCoercer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class PostHogEventTest {
    @Test
    fun `isExceptionEvent returns true for exception events`() {
        val event =
            PostHogEvent(
                event = PostHogEventName.EXCEPTION.event,
                distinctId = "test-user-id",
            )

        assertTrue(event.isExceptionEvent())
    }

    @Test
    fun `isExceptionEvent returns false for non-exception events`() {
        val event =
            PostHogEvent(
                event = "custom_event",
                distinctId = "test-user-id",
            )

        assertFalse(event.isExceptionEvent())
    }

    @Test
    fun `isFatalExceptionEvent returns true for fatal exception events`() {
        val event =
            PostHogEvent(
                event = PostHogEventName.EXCEPTION.event,
                distinctId = "test-user-id",
                properties =
                    mutableMapOf(
                        ThrowableCoercer.EXCEPTION_LEVEL_ATTRIBUTE to ThrowableCoercer.EXCEPTION_LEVEL_FATAL,
                    ),
            )

        assertTrue(event.isFatalExceptionEvent())
    }

    @Test
    fun `isFatalExceptionEvent returns false for non-fatal exception events`() {
        val event =
            PostHogEvent(
                event = PostHogEventName.EXCEPTION.event,
                distinctId = "test-user-id",
                properties =
                    mutableMapOf(
                        ThrowableCoercer.EXCEPTION_LEVEL_ATTRIBUTE to "error",
                    ),
            )

        assertFalse(event.isFatalExceptionEvent())
    }
}
