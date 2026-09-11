package com.posthog.internal

import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class PostHogPreSetupBufferTest {
    private fun capture(event: String) = PostHogPreSetupCall.Capture(event, null, null, null, null, null, Date())

    @Test
    fun `drains the calls oldest first`() {
        val sut = PostHogPreSetupBuffer()

        sut.add(capture("first"))
        sut.add(capture("second"))

        val (calls, dropped) = sut.drain()

        assertEquals(listOf("first", "second"), calls.map { (it as PostHogPreSetupCall.Capture).event })
        assertEquals(0, dropped)
    }

    @Test
    fun `draining empties the buffer`() {
        val sut = PostHogPreSetupBuffer()

        sut.add(capture("first"))
        sut.drain()

        assertTrue(sut.drain().first.isEmpty())
    }

    @Test
    fun `keeps the oldest calls and counts the ones that overflow`() {
        val sut = PostHogPreSetupBuffer(maxSize = 2)

        sut.add(capture("first"))
        sut.add(capture("second"))
        sut.add(capture("third"))

        val (calls, dropped) = sut.drain()

        assertEquals(listOf("first", "second"), calls.map { (it as PostHogPreSetupCall.Capture).event })
        assertEquals(1, dropped)
    }

    @Test
    fun `clear discards the buffered calls`() {
        val sut = PostHogPreSetupBuffer(maxSize = 1)

        sut.add(capture("first"))
        sut.add(capture("second"))
        sut.clear()

        val (calls, dropped) = sut.drain()

        assertTrue(calls.isEmpty())
        assertEquals(0, dropped)
    }
}
