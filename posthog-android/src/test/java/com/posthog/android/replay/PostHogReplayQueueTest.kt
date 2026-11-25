package com.posthog.android.replay

import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.android.PROJECT_TOKEN
import com.posthog.android.PostHogAndroidConfig
import com.posthog.internal.EndpointSpec
import com.posthog.internal.PostHogApi
import com.posthog.internal.PostHogApiEndpoint
import com.posthog.internal.PostHogLogger
import com.posthog.internal.PostHogQueue
import com.posthog.internal.PostHogQueueInterface
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogReplayQueueTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private val executors = mutableListOf<ExecutorService>()

    @AfterTest
    fun tearDown() {
        executors.forEach {
            if (it is PausedExecutorService) {
                it.shutdownNow()
            } else {
                it.shutdown()
                assertTrue(it.awaitTermination(2, TimeUnit.SECONDS))
            }
        }
        executors.clear()
    }

    private class FakeQueue : PostHogQueueInterface<PostHogEvent> {
        val events = mutableListOf<PostHogEvent>()
        var flushCallCount = 0
        var startCallCount = 0
        var stopCallCount = 0

        override val size: Int
            get() = events.size

        override fun add(record: PostHogEvent) {
            events.add(record)
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
        override var isActive: Boolean = true
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

    private data class RealReplayQueueFixture(
        val queue: PostHogReplayQueue,
        val config: PostHogConfig,
        val targetDir: File,
    )

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
        val config = PostHogConfig(PROJECT_TOKEN, "http://localhost:9001")
        return PostHogReplayQueue(config, fakeInnerQueue, storagePrefix, executor)
    }

    private fun createRealReplayQueue(): RealReplayQueueFixture {
        val config = PostHogConfig(PROJECT_TOKEN, "http://localhost:9001")
        val storagePrefix = tmpDir.newFolder().absolutePath
        val executor = createReplayExecutor()
        val innerQueue =
            PostHogQueue(
                config,
                EndpointSpec.snapshot(config, PostHogApi(config), storagePrefix),
                executor,
            )
        val queue = PostHogReplayQueue(config, innerQueue, storagePrefix, executor)
        return RealReplayQueueFixture(queue, config, File(storagePrefix, PROJECT_TOKEN))
    }

    private fun eventNamesInDirectory(
        config: PostHogConfig,
        dir: File,
    ): List<String> {
        return (dir.listFiles() ?: emptyArray())
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .mapNotNull { file ->
                file.inputStream().use { input ->
                    config.serializer.deserialize<PostHogEvent?>(input.reader().buffered())?.event
                }
            }
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
    fun `queue provider defers buffer cleanup even with replay disabled`() {
        val config = PostHogAndroidConfig(PROJECT_TOKEN)
        val storagePrefix = File(tmpDir.root, "replay").absolutePath
        val bufferDir = File("$storagePrefix-buffer", PROJECT_TOKEN)
        bufferDir.mkdirs()
        val leftover = File(bufferDir, "leftover.event").apply { writeText("old data") }
        val executor = createPausedExecutor()
        assertFalse(config.sessionReplay)

        val queue =
            config.queueProvider(config, PostHogApi(config), PostHogApiEndpoint.SNAPSHOT, storagePrefix, executor)
                as PostHogReplayQueue

        assertTrue(leftover.exists())
        assertEquals(0, queue.bufferDepth)
        assertNull(queue.bufferDurationMs)
        assertEquals(1, executor.queuedTaskCount)

        executor.runNext()

        assertFalse(leftover.exists())
        assertTrue(bufferDir.isDirectory)
        assertTrue(bufferDir.listFiles()!!.isEmpty())
    }

    @Test
    fun `construction does not create buffer directory`() {
        val storagePrefix = File(tmpDir.root, "replay").absolutePath
        val bufferDir = File("$storagePrefix-buffer", PROJECT_TOKEN)
        val executor = createPausedExecutor()
        val queue = createReplayQueue(createFakeQueue(), storagePrefix, executor)

        assertEquals(0, queue.bufferDepth)
        assertNull(queue.bufferDurationMs)
        assertFalse(bufferDir.exists())
        assertEquals(1, executor.queuedTaskCount)

        executor.runNext()

        assertTrue(bufferDir.isDirectory)
    }

    @Test
    fun `initial cleanup precedes buffered writes and subsequent clears`() {
        val config = PostHogConfig(PROJECT_TOKEN)
        val storagePrefix = File(tmpDir.root, "replay").absolutePath
        val bufferDir = File("$storagePrefix-buffer", PROJECT_TOKEN)
        bufferDir.mkdirs()
        val leftover = File(bufferDir, "leftover.event").apply { writeText("old data") }
        val executor = createPausedExecutor()
        val queue = createReplayQueue(createFakeQueue(), storagePrefix, executor)
        queue.bufferDelegate = MockReplayBufferDelegate().apply { isBuffering = true }

        queue.add(createTestEvent("before_clear"))
        queue.clearBuffer()
        queue.add(createTestEvent("after_clear"))

        assertTrue(leftover.exists())
        assertEquals(4, executor.queuedTaskCount)
        executor.runNext()
        assertFalse(leftover.exists())
        assertEquals(0, queue.bufferDepth)

        executor.runNext()
        assertEquals(listOf("before_clear"), eventNamesInDirectory(config, bufferDir))
        assertEquals(1, queue.bufferDepth)

        executor.runNext()
        assertTrue(bufferDir.listFiles()!!.isEmpty())
        assertEquals(0, queue.bufferDepth)

        executor.runNext()
        assertEquals(listOf("after_clear"), eventNamesInDirectory(config, bufferDir))
        assertEquals(1, queue.bufferDepth)
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
        executor.runNext()
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
        executor.runNext()
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
    fun `add drops snapshot when not buffering and delegate is inactive`() {
        val fakeInnerQueue = createFakeQueue()
        val queue = createReplayQueue(fakeInnerQueue)
        val delegate =
            MockReplayBufferDelegate().apply {
                isBuffering = false
                isActive = false
            }
        queue.bufferDelegate = delegate

        queue.add(createTestEvent("after_stop"))

        assertEquals(0, fakeInnerQueue.events.size)
        assertEquals(0, queue.bufferDepth)
    }

    @Test
    fun `add drops snapshot routed out of buffer when delegate becomes inactive`() {
        val fakeInnerQueue = createFakeQueue()
        val executor = createPausedExecutor()
        val queue = createReplayQueue(fakeInnerQueue, executor = executor)
        executor.runNext()
        val delegate =
            MockReplayBufferDelegate().apply {
                isBuffering = true
                isActive = true
            }
        queue.bufferDelegate = delegate

        queue.add(createTestEvent("in_flight"))
        // Recording stopped and buffering resolved (fresh-false) before the executor task ran.
        delegate.isBuffering = false
        delegate.isActive = false

        executor.runNext()

        assertEquals(0, fakeInnerQueue.events.size)
        assertEquals(0, queue.bufferDepth)
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
    fun `migrateBufferToQueue moves buffered events into real replay queue`() {
        val fixture = createRealReplayQueue()
        val queue = fixture.queue
        val delegate = MockReplayBufferDelegate().apply { isBuffering = true }
        queue.bufferDelegate = delegate

        queue.add(createTestEvent("snapshot_1"))
        Thread.sleep(10)
        queue.add(createTestEvent("snapshot_2"))
        Thread.sleep(10)
        queue.add(createTestEvent("snapshot_3"))
        awaitReplayExecutors()

        queue.migrateBufferToQueue()

        assertEquals(0, queue.bufferDepth)
        assertEquals(3, queue.depth)
        assertEquals(
            listOf("snapshot_1", "snapshot_2", "snapshot_3"),
            eventNamesInDirectory(fixture.config, fixture.targetDir),
        )
    }

    @Test
    fun `delegate can trigger successful migration into real replay queue`() {
        val fixture = createRealReplayQueue()
        val queue = fixture.queue
        val migrated = CountDownLatch(1)
        val migrationExecutor = createReplayExecutor()
        val delegate =
            object : PostHogReplayBufferDelegate {
                override var isBuffering: Boolean = true
                override val isActive: Boolean = true
                private var callCount = 0

                override fun onReplayBufferSnapshot(replayQueue: PostHogReplayQueue) {
                    callCount++
                    if (callCount >= 3) {
                        isBuffering = false
                        migrationExecutor.execute {
                            replayQueue.migrateBufferToQueue()
                            migrated.countDown()
                        }
                    }
                }
            }
        queue.bufferDelegate = delegate

        queue.add(createTestEvent("snapshot_1"))
        Thread.sleep(10)
        queue.add(createTestEvent("snapshot_2"))
        Thread.sleep(10)
        queue.add(createTestEvent("snapshot_3"))

        assertTrue(migrated.await(2, TimeUnit.SECONDS))
        awaitReplayExecutors()

        assertEquals(0, queue.bufferDepth)
        assertEquals(3, queue.depth)
        assertEquals(
            listOf("snapshot_1", "snapshot_2", "snapshot_3"),
            eventNamesInDirectory(fixture.config, fixture.targetDir),
        )
        assertEquals(false, delegate.isBuffering)
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
        awaitReplayExecutors()

        assertEquals(0, queue.bufferDepth)
    }

    @Test
    fun `migrateBufferToQueue is a no-op while a buffer clear is pending and works after it ran`() {
        val fixture = createRealReplayQueue()
        val queue = fixture.queue
        queue.bufferDelegate = MockReplayBufferDelegate().apply { isBuffering = true }
        queue.add(createTestEvent("snapshot_1"))
        queue.add(createTestEvent("snapshot_2"))
        awaitReplayExecutors()
        val executor = executors.single()
        val release = CountDownLatch(1)
        val busy = CountDownLatch(1)
        executor.execute {
            busy.countDown()
            release.await(2, TimeUnit.SECONDS)
        }
        assertTrue(busy.await(2, TimeUnit.SECONDS))

        try {
            queue.clearBuffer()
            assertTrue(queue.isBufferClearPending)

            queue.migrateBufferToQueue()

            assertEquals(2, queue.bufferDepth)
            assertEquals(0, queue.depth)
        } finally {
            release.countDown()
        }
        awaitReplayExecutors()
        assertFalse(queue.isBufferClearPending)
        assertEquals(0, queue.bufferDepth)

        queue.add(createTestEvent("snapshot_3"))
        awaitReplayExecutors()
        queue.migrateBufferToQueue()

        assertEquals(0, queue.bufferDepth)
        assertEquals(listOf("snapshot_3"), eventNamesInDirectory(fixture.config, fixture.targetDir))
    }

    @Test
    fun `pending buffer clear count returns to zero when the clear task throws`() {
        val config =
            PostHogConfig(PROJECT_TOKEN, "http://localhost:9001").apply {
                logger =
                    object : PostHogLogger {
                        override fun log(message: String) {
                            if (message == "Replay buffer cleared") throw IllegalStateException("boom")
                        }

                        override fun isEnabled(): Boolean = true
                    }
            }
        val executor = createPausedExecutor()
        val queue = PostHogReplayQueue(config, createFakeQueue(), tmpDir.newFolder().absolutePath, executor)
        executor.runNext()

        queue.clearBuffer()
        assertTrue(queue.isBufferClearPending)

        assertFailsWith<IllegalStateException> { executor.runNext() }
        assertFalse(queue.isBufferClearPending)
    }

    @Test
    fun `pending buffer clear count returns to zero when the clear cannot be scheduled`() {
        val executor = createReplayExecutor()
        val queue = createReplayQueue(createFakeQueue(), executor = executor)
        awaitReplayExecutors()
        executor.shutdown()

        queue.clearBuffer()

        assertFalse(queue.isBufferClearPending)
    }

    @Test
    fun `clearBuffer does not affect inner queue events`() {
        val fakeInnerQueue = createFakeQueue()
        val queue = createReplayQueue(fakeInnerQueue)
        val delegate = MockReplayBufferDelegate().apply { isBuffering = false }
        queue.bufferDelegate = delegate
        queue.add(createTestEvent("direct_1"))

        delegate.isBuffering = true
        queue.add(createTestEvent("buffered_1"))
        awaitReplayExecutors()
        assertEquals(1, queue.bufferDepth)
        assertEquals(1, fakeInnerQueue.events.size)

        queue.clearBuffer()
        awaitReplayExecutors()

        assertEquals(0, queue.bufferDepth)
        assertEquals(1, fakeInnerQueue.events.size)
        assertEquals("direct_1", fakeInnerQueue.events.first().event)
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
        awaitReplayExecutors()

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
                override val isActive: Boolean = true

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

    private class MaskingDelegate(override var isBuffering: Boolean) : PostHogReplayBufferDelegate {
        override val isActive: Boolean = true
        var captureGeneration: Int? = null
        var generation: Int = 0

        override fun onReplayBufferSnapshot(replayQueue: PostHogReplayQueue) {}

        override fun currentCaptureMaskingGeneration(): Int? = captureGeneration

        override fun isMaskingGenerationStale(generation: Int): Boolean = generation != this.generation
    }

    @Test
    fun `buffered add discards a frame whose masking generation went stale before its task ran`() {
        val executor = createPausedExecutor()
        val queue = createReplayQueue(createFakeQueue(), executor = executor)
        executor.runNext()
        val delegate = MaskingDelegate(isBuffering = true)
        queue.bufferDelegate = delegate

        delegate.captureGeneration = 0
        queue.add(createTestEvent("stale"))
        delegate.captureGeneration = null
        delegate.generation = 1
        executor.runNext()

        delegate.captureGeneration = 1
        queue.add(createTestEvent("current"))
        delegate.captureGeneration = null
        queue.add(createTestEvent("unstamped"))
        executor.runNext()
        executor.runNext()

        assertEquals(2, queue.bufferDepth)
    }

    @Test
    fun `direct add discards a frame with a stale masking generation`() {
        val innerQueue = createFakeQueue()
        val queue = createReplayQueue(innerQueue)
        val delegate = MaskingDelegate(isBuffering = false)
        queue.bufferDelegate = delegate
        delegate.generation = 1

        delegate.captureGeneration = 0
        queue.add(createTestEvent("stale"))
        delegate.captureGeneration = 1
        queue.add(createTestEvent("current"))
        delegate.captureGeneration = null
        queue.add(createTestEvent("unstamped"))
        awaitReplayExecutors()

        assertEquals(listOf("current", "unstamped"), innerQueue.events.map { it.event })
    }

    @Test
    fun `willBufferSnapshot runs on the add caller before the snapshot reaches the buffer and only while buffering`() {
        val executor = createPausedExecutor()
        val innerQueue = createFakeQueue()
        val queue = createReplayQueue(innerQueue, executor = executor)
        executor.runNext()
        val bufferDepthsAtNotice = mutableListOf<Int>()
        val delegate =
            object : PostHogReplayBufferDelegate {
                override var isBuffering: Boolean = true
                override val isActive: Boolean = true

                override fun onReplayBufferSnapshot(replayQueue: PostHogReplayQueue) {}

                override fun willBufferSnapshot() {
                    bufferDepthsAtNotice.add(queue.bufferDepth)
                }
            }
        queue.bufferDelegate = delegate

        queue.add(createTestEvent("buffered"))
        assertEquals(listOf(0), bufferDepthsAtNotice)
        executor.runNext()
        assertEquals(1, queue.bufferDepth)

        delegate.isBuffering = false
        queue.add(createTestEvent("direct"))

        assertEquals(listOf(0), bufferDepthsAtNotice)
        assertEquals(listOf("direct"), innerQueue.events.map { it.event })
    }
}
