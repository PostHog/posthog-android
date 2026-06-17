package com.posthog.internal.errortracking

import com.posthog.PostHogConfig
import com.posthog.internal.PostHogSerializer
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

        sut.add("User tapped Checkout", ts, mapOf("screen" to "cart", "qty" to 2))

        val steps = sut.snapshot()
        assertEquals(1, steps.size)
        val step = steps.first()
        assertEquals("User tapped Checkout", step[PostHogExceptionStepsBuffer.MESSAGE_KEY])
        assertEquals("cart", step["screen"])
        // the step is normalized to its JSON wire form once, exactly as event properties
        // are serialized: the Date becomes an ISO-8601 String, scalars are preserved
        assertTrue(step[PostHogExceptionStepsBuffer.TIMESTAMP_KEY] is String)
        assertEquals(2, (step["qty"] as Number).toInt())
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
        // budget holds only a fraction of the five equally-sized steps
        val sut = getSut(maxBytes = 120)

        repeat(5) { index ->
            sut.add("step-$index", Date(), mapOf("pad" to "xxxxxxxxxx"))
        }

        val messages = sut.snapshot().map { it[PostHogExceptionStepsBuffer.MESSAGE_KEY] }
        // eviction happened and left a contiguous newest-first suffix (no stale oldest,
        // no accounting drift that would keep a non-contiguous set)
        assertTrue(messages.isNotEmpty())
        assertTrue(messages.size < 5)
        assertEquals((5 - messages.size until 5).map { "step-$it" }, messages)
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

        // NaN cannot be represented in JSON; the safe map serializer drops it. The
        // step is still stored and the representable sibling property survives.
        sut.add("msg", ts, mapOf("ok" to "value", "bad" to Double.NaN))

        val step = sut.snapshot().first()
        assertEquals("value", step["ok"])
        assertTrue(!step.containsKey("bad"))
    }

    @Test
    fun `concurrent adds and snapshots stay consistent`() {
        // budget large enough that nothing is evicted, so the surviving count is exact
        val sut = getSut(maxBytes = 1 shl 20)
        val threadCount = 8
        val perThread = 200

        val pool = Executors.newFixedThreadPool(threadCount)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        repeat(threadCount) { t ->
            pool.submit {
                start.await()
                repeat(perThread) { i ->
                    sut.add("t$t-$i", Date(), mapOf("i" to i))
                    // interleave reads to race add() against snapshot()
                    sut.snapshot()
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()

        // every add fit within budget, so all steps are present with no lost updates
        assertEquals(threadCount * perThread, sut.snapshot().size)
    }
}
