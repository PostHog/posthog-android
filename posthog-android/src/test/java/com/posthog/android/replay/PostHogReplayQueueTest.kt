package com.posthog.android.replay

import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.android.API_KEY
import com.posthog.internal.PostHogQueueInterface
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.UUID
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

internal class PostHogReplayQueueTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private val executors = mutableListOf<ExecutorService>()

    @AfterTest
    fun tearDown() {
        executors.forEach { it.shutdownNow() }
        executors.clear()
    }

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

    private class PausedExecutorService : AbstractExecutorService() {
        private val tasks = LinkedBlockingQueue<Runnable>()

        @Volatile
        private var shutdown = false

        val queuedTaskCount: Int
            get() = tasks.size

        override fun execute(command: Runnable) {
            if (!shutdown) {
                tasks.add(command)
            }
        }

        fun runNext() {
            val task = tasks.poll(2, TimeUnit.SECONDS) ?: error("Timed out waiting for executor task")
            task.run()
        }

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdown = true
            val pendingTasks = mutableListOf<Runnable>()
            tasks.drainTo(pendingTasks)
            return pendingTasks
        }

        override fun isShutdown(): Boolean = shutdown

        override fun isTerminated(): Boolean = shutdown && tasks.isEmpty()

        override fun awaitTermination(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = isTerminated
    }

    private fun createFakeQueue(): FakeQueue {
        return FakeQueue()
    }

    private fun createReplayExecutor(): ExecutorService {
        val executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "PostHogReplayQueueTest").apply { isDaemon = true }
            }
        executors.add(executor)
        return executor
    }

    private fun createPausedExecutor(): PausedExecutorService {
        val executor = PausedExecutorService()
        executors.add(executor)
        return executor
    }

    private fun awaitReplayExecutors() {
        executors
            .filterNot { it is PausedExecutorService }
            .forEach { it.submit {}.get(2, TimeUnit.SECONDS) }
    }

    private fun createReplayQueue(
        fakeInnerQueue: FakeQueue,
        storagePrefix: String = tmpDir.newFolder().absolutePath,
        executor: ExecutorService = createReplayExecutor(),
    ): PostHogReplayQueue {
        val config = PostHogConfig(API_KEY, "http://localhost:9001")
        return PostHogReplayQueue(config, fakeInnerQueue, storagePrefix, executor)
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
        awaitReplayExecutors()

        assertEquals(2, queue.bufferDepth)
        assertEquals(0, fakeInnerQueue.events.size)
        assertEquals(2, delegate.didBufferSnapshotCallCount)
    }

    @Test
    fun `buffered add schedules disk write on replay executor`() {
        val fakeInnerQueue = createFakeQueue()
        val executor = createPausedExecutor()
        val queue = createReplayQueue(fakeInnerQueue, executor = executor)
        val delegate = MockReplayBufferDelegate().apply { isBuffering = true }
        queue.bufferDelegate = delegate

        queue.add(createTestEvent("snapshot_1"))

        assertEquals(1, executor.queuedTaskCount)
        assertEquals(0, queue.bufferDepth)
        assertEquals(0, fakeInnerQueue.events.size)
        assertEquals(0, delegate.didBufferSnapshotCallCount)

        executor.runNext()

        assertEquals(1, queue.bufferDepth)
        assertEquals(0, fakeInnerQueue.events.size)
        assertEquals(1, delegate.didBufferSnapshotCallCount)
    }

    @Test
    fun `buffering state is rechecked on replay executor`() {
        val fakeInnerQueue = createFakeQueue()
        val executor = createPausedExecutor()
        val queue = createReplayQueue(fakeInnerQueue, executor = executor)
        val delegate = MockReplayBufferDelegate().apply { isBuffering = true }
        queue.bufferDelegate = delegate

        queue.add(createTestEvent("snapshot_1"))
        delegate.isBuffering = false

        executor.runNext()

        assertEquals(0, queue.bufferDepth)
        assertEquals(1, fakeInnerQueue.events.size)
        assertEquals(0, delegate.didBufferSnapshotCallCount)
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
        awaitReplayExecutors()

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
        awaitReplayExecutors()

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
        awaitReplayExecutors()

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
        awaitReplayExecutors()
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
        awaitReplayExecutors()
        assertEquals(2, queue.bufferDepth)

        queue.migrateBufferToQueue()
        delegate.isBuffering = false

        queue.add(createTestEvent("direct_1"))

        assertEquals(2, queue.bufferDepth)
        assertEquals(1, fakeInnerQueue.events.size)
        assertEquals("direct_1", fakeInnerQueue.events.first().event)
    }

    @Test
    fun `delegate can disable buffering before later adds`() {
        val innerQueue = createFakeQueue()
        val queue = createReplayQueue(innerQueue)

        val delegate =
            object : PostHogReplayBufferDelegate {
                override var isBuffering: Boolean = true

                override fun onReplayBufferSnapshot(replayQueue: PostHogReplayQueue) {
                    if (isBuffering && replayQueue.bufferDepth >= 2) {
                        isBuffering = false
                    }
                }
            }
        queue.bufferDelegate = delegate

        queue.add(createTestEvent("buffered_1"))
        queue.add(createTestEvent("buffered_2"))
        queue.add(createTestEvent("direct_after_disable"))
        awaitReplayExecutors()

        assertEquals(2, queue.bufferDepth)
        assertEquals(1, innerQueue.events.size)
        assertEquals("direct_after_disable", innerQueue.events.first().event)
    }
}
