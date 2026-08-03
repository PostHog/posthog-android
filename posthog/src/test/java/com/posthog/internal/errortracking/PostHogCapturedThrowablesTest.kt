package com.posthog.internal.errortracking

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class PostHogCapturedThrowablesTest {
    @Test
    fun `first mark returns true, repeat mark of same instance returns false`() {
        val throwable = RuntimeException("boom")

        assertTrue(PostHogCapturedThrowables.markAndCheck(throwable), "first sighting should be captured")
        assertFalse(PostHogCapturedThrowables.markAndCheck(throwable), "same instance should be deduped")
    }

    @Test
    fun `distinct instances that are equal by value are both captured`() {
        // A Throwable subclass with value equality must NOT make two distinct instances collide:
        // dedup is strictly instance-identity based.
        val a = ValueEqualThrowable("same")
        val b = ValueEqualThrowable("same")

        // Sanity: they are equal by value but distinct instances.
        assertTrue(a == b)
        assertFalse(a === b)

        assertTrue(PostHogCapturedThrowables.markAndCheck(a), "first instance captured")
        assertTrue(PostHogCapturedThrowables.markAndCheck(b), "second, value-equal instance must still be captured")
        assertFalse(PostHogCapturedThrowables.markAndCheck(a), "re-marking the first instance is still deduped")
    }

    private class ValueEqualThrowable(private val token: String) : RuntimeException(token) {
        override fun equals(other: Any?): Boolean = other is ValueEqualThrowable && other.token == token

        override fun hashCode(): Int = token.hashCode()
    }
}
