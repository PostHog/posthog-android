package com.posthog.android.replay

import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.android.API_KEY
import com.posthog.internal.PostHogQueueInterface
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

internal class PostHogReplayQueueTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private class FakeQueue : PostHogQueueInterface {
        val events = mutableListOf<PostHogEvent>()
        var flushCallCount = 0
        var startCallCount = 0
        var stopCallCount = 0

        override fun add(event: PostHogEvent) {
            events.add(event)
        }

        override fun flush() {
            flushCallCount++
        }

        override fun start() {
            startCallCount++
        }

        override fun stop() {
            stopCallCount++
        }

        override fun clear() {
            events.clear()
        }
    }

    private class MockReplayBufferDelegate : PostHogReplayBufferDelegate {
        override var isBuffering: Boolean = false
        var didBufferSnapshotCallCount: Int = 0
        var lastReplayQueue: PostHogReplayQueue? = null

        override fun onReplayBufferSnapshot(replayQueue: PostHogReplayQueue) {
            didBufferSnapshotCallCount++
            lastReplayQueue = replayQueue
        }
    }

    private fun createFakeQueue(): FakeQueue {
        return FakeQueue()
    }

    private fun createReplayQueue(
        fakeInnerQueue: FakeQueue,
        storagePrefix: String = tmpDir.newFolder().absolutePath,
    ): PostHogReplayQueue {
        val config = PostHogConfig(API_KEY, "http://localhost:9001")
        return PostHogReplayQueue(config, fakeInnerQueue, storagePrefix)
    }

    private fun createTestEvent(name: String = "test_event"): PostHogEvent {
        return PostHogEvent(
            event = name,
            distinctId = "test-user",
            properties = mutableMapOf("test" to "value"),
            uuid = UUID.randomUUID(),
        )
    }

    @Test
    fun `add routes to buffer when delegate isBuffering is true`() {
        val fakeInnerQueue = createFakeQueue()
        val queue = createReplayQueue(fakeInnerQueue)
        val delegate = MockReplayBufferDelegate().apply { isBuffering = true }
        queue.bufferDelegate = delegate

        queue.add(createTestEvent("snapshot_1"))
        queue.add(createTestEvent("snapshot_2"))

        assertEquals(2, queue.bufferDepth)
        assertEquals(0, fakeInnerQueue.events.size)
        assertEquals(2, delegate.didBufferSnapshotCallCount)
    }

    @Test
    fun `add routes to inner queue when delegate isBuffering is false`() {
        val fakeInnerQueue = createFakeQueue()
        val queue = createReplayQueue(fakeInnerQueue)
        val delegate = MockReplayBufferDelegate().apply { isBuffering = false }
        queue.bufferDelegate = delegate

        queue.add(createTestEvent("snapshot_1"))
        queue.add(createTestEvent("snapshot_2"))

        assertEquals(0, queue.bufferDepth)
        assertEquals(2, fakeInnerQueue.events.size)
        assertEquals(0, delegate.didBufferSnapshotCallCount)
    }

    @Test
    fun `flush is suppressed when buffering`() {
        val fakeInnerQueue = createFakeQueue()
        val queue = createReplayQueue(fakeInnerQueue)
        val delegate = MockReplayBufferDelegate().apply { isBuffering = true }
        queue.bufferDelegate = delegate

        queue.add(createTestEvent("snapshot_1"))
        queue.flush()

        assertEquals(1, queue.bufferDepth)
        assertEquals(0, fakeInnerQueue.flushCallCount)
    }

    @Test
    fun `flush delegates to inner queue when not buffering`() {
        val fakeInnerQueue = createFakeQueue()
        val queue = createReplayQueue(fakeInnerQueue)
        val delegate = MockReplayBufferDelegate().apply { isBuffering = false }
        queue.bufferDelegate = delegate

        queue.flush()

        assertEquals(1, fakeInnerQueue.flushCallCount)
    }

    @Test
    fun `migrateBufferToQueue noops when inner queue is not PostHogQueue`() {
        val fakeInnerQueue = createFakeQueue()
        val queue = createReplayQueue(fakeInnerQueue)
        val delegate = MockReplayBufferDelegate().apply { isBuffering = true }
        queue.bufferDelegate = delegate

        queue.add(createTestEvent("snapshot_1"))
        queue.add(createTestEvent("snapshot_2"))
        queue.add(createTestEvent("snapshot_3"))

        assertEquals(3, queue.bufferDepth)
        assertEquals(0, fakeInnerQueue.events.size)

        queue.migrateBufferToQueue()

        assertEquals(3, queue.bufferDepth)
        assertEquals(0, fakeInnerQueue.events.size)
    }

    @Test
    fun `migrateBufferToQueue handles empty buffer gracefully`() {
        val fakeInnerQueue = createFakeQueue()
        val queue = createReplayQueue(fakeInnerQueue)

        queue.migrateBufferToQueue()

        assertEquals(0, queue.bufferDepth)
        assertEquals(0, fakeInnerQueue.events.size)
    }

    @Test
    fun `clearBuffer discards all buffered events`() {
        val fakeInnerQueue = createFakeQueue()
        val queue = createReplayQueue(fakeInnerQueue)
        val delegate = MockReplayBufferDelegate().apply { isBuffering = true }
        queue.bufferDelegate = delegate

        queue.add(createTestEvent("snapshot_1"))
        queue.add(createTestEvent("snapshot_2"))

        assertEquals(2, queue.bufferDepth)

        queue.clearBuffer()

        assertEquals(0, queue.bufferDepth)
    }

    @Test
    fun `clear removes both buffer and inner queue events`() {
        val fakeInnerQueue = createFakeQueue()
        val queue = createReplayQueue(fakeInnerQueue)

        val delegate = MockReplayBufferDelegate().apply { isBuffering = false }
        queue.bufferDelegate = delegate
        queue.add(createTestEvent("direct_1"))
        assertEquals(1, fakeInnerQueue.events.size)

        delegate.isBuffering = true
        queue.add(createTestEvent("buffered_1"))
        assertEquals(1, queue.bufferDepth)

        queue.clear()

        assertEquals(0, queue.bufferDepth)
        assertEquals(0, fakeInnerQueue.events.size)
    }

    @Test
    fun `start and stop delegate to inner queue`() {
        val fakeInnerQueue = createFakeQueue()
        val queue = createReplayQueue(fakeInnerQueue)

        queue.start()
        queue.stop()

        assertEquals(1, fakeInnerQueue.startCallCount)
        assertEquals(1, fakeInnerQueue.stopCallCount)
    }

    @Test
    fun `events go directly to inner queue after buffering disabled even if migration noops`() {
        val fakeInnerQueue = createFakeQueue()
        val queue = createReplayQueue(fakeInnerQueue)
        val delegate = MockReplayBufferDelegate().apply { isBuffering = true }
        queue.bufferDelegate = delegate

        queue.add(createTestEvent("buffered_1"))
        queue.add(createTestEvent("buffered_2"))
        assertEquals(2, queue.bufferDepth)

        queue.migrateBufferToQueue()
        delegate.isBuffering = false

        queue.add(createTestEvent("direct_1"))

        assertEquals(2, queue.bufferDepth)
        assertEquals(1, fakeInnerQueue.events.size)
        assertEquals("direct_1", fakeInnerQueue.events.first().event)
    }

    @Test
    fun `delegate can disable buffering even if migration noops`() {
        val innerQueue = createFakeQueue()
        val queue = createReplayQueue(innerQueue)

        val delegate =
            object : PostHogReplayBufferDelegate {
                override var isBuffering: Boolean = true

                override fun onReplayBufferSnapshot(replayQueue: PostHogReplayQueue) {
                    if (isBuffering && replayQueue.bufferDepth >= 2) {
                        isBuffering = false
                        replayQueue.migrateBufferToQueue()
                    }
                }
            }
        queue.bufferDelegate = delegate

        queue.add(createTestEvent("buffered_1"))
        queue.add(createTestEvent("buffered_2"))
        queue.add(createTestEvent("direct_after_disable"))

        assertEquals(2, queue.bufferDepth)
        assertEquals(1, innerQueue.events.size)
        assertEquals("direct_after_disable", innerQueue.events.first().event)
    }
}
