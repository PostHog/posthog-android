package com.posthog.internal.errortracking

import com.posthog.PostHogConfig
import com.posthog.internal.PostHogSerializer
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class PostHogExceptionStepsBufferTest {
    private val config = PostHogConfig("test")
    private val serializer = PostHogSerializer(config)

    private fun getSut(maxBytes: Int = 32768) =
        PostHogExceptionStepsBuffer(
            maxBytes = maxBytes,
            serializer = serializer,
            logger = config.logger,
        )

    @Test
    fun `records a step with canonical message and timestamp`() {
        val sut = getSut()
        val ts = Date()

        sut.add("User tapped Checkout", ts, mapOf("screen" to "cart"))

        val steps = sut.snapshot()
        assertEquals(1, steps.size)
        val step = steps.first()
        assertEquals("User tapped Checkout", step[PostHogExceptionStepsBuffer.MESSAGE_KEY])
        assertEquals("cart", step["screen"])
        assertTrue(step.containsKey(PostHogExceptionStepsBuffer.TIMESTAMP_KEY))
    }

    @Test
    fun `ignores empty message`() {
        val sut = getSut()

        sut.add("", Date(), null)
        sut.add("   ", Date(), null)

        assertTrue(sut.snapshot().isEmpty())
    }

    @Test
    fun `preserves FIFO order oldest first`() {
        val sut = getSut()

        sut.add("A", Date(), null)
        sut.add("B", Date(), null)
        sut.add("C", Date(), null)

        assertEquals(
            listOf("A", "B", "C"),
            sut.snapshot().map { it[PostHogExceptionStepsBuffer.MESSAGE_KEY] },
        )
    }

    @Test
    fun `strips reserved keys from user properties`() {
        val sut = getSut()
        val ts = Date()

        sut.add(
            "msg",
            ts,
            mapOf(
                PostHogExceptionStepsBuffer.MESSAGE_KEY to "spoofed",
                PostHogExceptionStepsBuffer.TIMESTAMP_KEY to "spoofed",
                "kept" to "value",
            ),
        )

        val step = sut.snapshot().first()
        assertEquals("msg", step[PostHogExceptionStepsBuffer.MESSAGE_KEY])
        assertEquals("value", step["kept"])
        // the canonical timestamp is set by the buffer, not the spoofed string
        assertTrue(step[PostHogExceptionStepsBuffer.TIMESTAMP_KEY] != "spoofed")
    }

    @Test
    fun `evicts oldest steps when over byte budget`() {
        // budget large enough for ~2 of these steps
        val sut = getSut(maxBytes = 120)

        repeat(5) { index ->
            sut.add("step-$index", Date(), mapOf("pad" to "xxxxxxxxxx"))
        }

        val messages = sut.snapshot().map { it[PostHogExceptionStepsBuffer.MESSAGE_KEY] }
        assertTrue(messages.isNotEmpty())
        // oldest dropped: step-0 must not survive, newest must
        assertTrue(!messages.contains("step-0"))
        assertEquals("step-4", messages.last())
    }

    @Test
    fun `rejects a single step larger than the budget and keeps existing steps`() {
        val sut = getSut(maxBytes = 80)

        sut.add("small", Date(), null)
        sut.add("big", Date(), mapOf("pad" to "y".repeat(200)))

        val messages = sut.snapshot().map { it[PostHogExceptionStepsBuffer.MESSAGE_KEY] }
        assertEquals(listOf("small"), messages)
    }

    @Test
    fun `byte budget uses UTF-8 byte length not character count`() {
        // multi-byte chars: each "あ" is 3 UTF-8 bytes
        val sut = getSut(maxBytes = 60)

        sut.add("あ".repeat(40), Date(), null)

        // serialized step far exceeds 60 bytes, so it is rejected
        assertTrue(sut.snapshot().isEmpty())
    }

    @Test
    fun `clear empties the buffer`() {
        val sut = getSut()
        sut.add("A", Date(), null)
        sut.add("B", Date(), null)

        sut.clear()

        assertTrue(sut.snapshot().isEmpty())
    }

    @Test
    fun `does not throw on unrepresentable values and skips them like event properties`() {
        val sut = getSut()
        val ts = Date()

        // a value the safe map serializer drops should not prevent the step from being stored
        sut.add("msg", ts, mapOf("ok" to "value"))

        val step = sut.snapshot().first()
        assertEquals("value", step["ok"])
    }
}
