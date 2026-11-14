package com.posthog.internal

import kotlin.test.Test
import kotlin.test.assertTrue

internal class PostHogDeviceDateProviderTest {
    private val provider = PostHogDeviceDateProvider()

    @Test
    fun `current date uses the wall clock`() {
        val before = System.currentTimeMillis()
        val actual = provider.currentDate().time
        val after = System.currentTimeMillis()

        assertTrue(actual in before..after)
    }

    @Test
    fun `adding seconds uses elapsed milliseconds without integer overflow`() {
        listOf(Int.MIN_VALUE, -3601, 0, 3601, Int.MAX_VALUE).forEach { seconds ->
            val offset = seconds * 1000L
            val before = System.currentTimeMillis() + offset
            val actual = provider.addSecondsToCurrentDate(seconds).time
            val after = System.currentTimeMillis() + offset

            assertTrue(actual in before..after, "Unexpected date for $seconds seconds: $actual")
        }
    }
}
