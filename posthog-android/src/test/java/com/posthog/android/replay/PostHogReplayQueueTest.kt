package com.posthog.android.replay

import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.android.API_KEY
import com.posthog.internal.PostHogQueueInterface
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        val fakeInnerQueue = FakeQueue()
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
        val fakeInnerQueue = FakeQueue()
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
        val fakeInnerQueue = FakeQueue()
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
        val fakeInnerQueue = FakeQueue()
        val queue = createReplayQueue(fakeInnerQueue)
        val delegate = MockReplayBufferDelegate().apply { isBuffering = false }
        queue.bufferDelegate = delegate

        queue.flush()

        assertEquals(1, fakeInnerQueue.flushCallCount)
    }

    @Test
    fun `migrateBufferToQueue moves buffered events to inner queue`() {
        val fakeInnerQueue = FakeQueue()
        val queue = createReplayQueue(fakeInnerQueue)
        val delegate = MockReplayBufferDelegate().apply { isBuffering = true }
        queue.bufferDelegate = delegate

        queue.add(createTestEvent("snapshot_1"))
        queue.add(createTestEvent("snapshot_2"))
        queue.add(createTestEvent("snapshot_3"))

        assertEquals(3, queue.bufferDepth)
        assertEquals(0, fakeInnerQueue.events.size)

        queue.migrateBufferToQueue()

        assertEquals(0, queue.bufferDepth)
        assertEquals(3, fakeInnerQueue.events.size)
        assertEquals(listOf("snapshot_1", "snapshot_2", "snapshot_3"), fakeInnerQueue.events.map { it.event })
    }

    @Test
    fun `migrateBufferToQueue handles empty buffer gracefully`() {
        val fakeInnerQueue = FakeQueue()
        val queue = createReplayQueue(fakeInnerQueue)

        queue.migrateBufferToQueue()

        assertEquals(0, queue.bufferDepth)
        assertEquals(0, fakeInnerQueue.events.size)
    }

    @Test
    fun `clearBuffer discards all buffered events`() {
        val fakeInnerQueue = FakeQueue()
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
        val fakeInnerQueue = FakeQueue()
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
        val fakeInnerQueue = FakeQueue()
        val queue = createReplayQueue(fakeInnerQueue)

        queue.start()
        queue.stop()

        assertEquals(1, fakeInnerQueue.startCallCount)
        assertEquals(1, fakeInnerQueue.stopCallCount)
    }

    @Test
    fun `events go directly to inner queue after migration when buffering disabled`() {
        val fakeInnerQueue = FakeQueue()
        val queue = createReplayQueue(fakeInnerQueue)
        val delegate = MockReplayBufferDelegate().apply { isBuffering = true }
        queue.bufferDelegate = delegate

        queue.add(createTestEvent("buffered_1"))
        queue.add(createTestEvent("buffered_2"))
        assertEquals(2, queue.bufferDepth)

        queue.migrateBufferToQueue()
        delegate.isBuffering = false

        queue.add(createTestEvent("direct_1"))

        assertEquals(0, queue.bufferDepth)
        assertEquals(3, fakeInnerQueue.events.size)
        assertTrue(fakeInnerQueue.events.map { it.event }.containsAll(listOf("buffered_1", "buffered_2", "direct_1")))
    }

    @Test
    fun `concurrent snapshot goes direct when delegate flips buffering before migration`() {
        val migrationStarted = CountDownLatch(1)
        val allowMigrationToContinue = CountDownLatch(1)

        val innerQueue =
            object : PostHogQueueInterface {
                val events = mutableListOf<PostHogEvent>()

                override fun add(event: PostHogEvent) {
                    synchronized(events) { events.add(event) }
                    if (event.event == "buffered_1") {
                        migrationStarted.countDown()
                        allowMigrationToContinue.await(2, TimeUnit.SECONDS)
                    }
                }

                override fun flush() {}

                override fun start() {}

                override fun stop() {}

                override fun clear() {
                    synchronized(events) { events.clear() }
                }
            }

        val config = PostHogConfig(API_KEY, "http://localhost:9001")
        val queue = PostHogReplayQueue(config, innerQueue, tmpDir.newFolder().absolutePath)

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

        val migrationThread =
            Thread {
                queue.add(createTestEvent("buffered_2"))
            }.apply { start() }

        assertTrue(migrationStarted.await(2, TimeUnit.SECONDS))

        queue.add(createTestEvent("concurrent_direct"))

        allowMigrationToContinue.countDown()
        migrationThread.join(2000)

        assertEquals(0, queue.bufferDepth)
        synchronized(innerQueue.events) {
            assertEquals(3, innerQueue.events.size)
            assertTrue(innerQueue.events.map { it.event }.containsAll(listOf("buffered_1", "buffered_2", "concurrent_direct")))
        }
    }
}
