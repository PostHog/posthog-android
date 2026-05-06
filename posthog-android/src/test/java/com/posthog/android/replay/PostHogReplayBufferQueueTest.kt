package com.posthog.android.replay

import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.android.API_KEY
import com.posthog.internal.PostHogApi
import com.posthog.internal.PostHogApiEndpoint
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogReplayBufferQueueTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private val executors = mutableListOf<ExecutorService>()

    private class FakeQueue : PostHogQueueInterface {
        val events = mutableListOf<PostHogEvent>()

        override fun add(event: PostHogEvent) {
            events.add(event)
        }

        override fun flush() {
        }

        override fun start() {
        }

        override fun stop() {
        }

        override fun clear() {
            events.clear()
        }
    }

    private class PausedExecutorService : AbstractExecutorService() {
        private val tasks = LinkedBlockingQueue<Runnable>()

        @Volatile
        private var shutdown = false

        override fun execute(command: Runnable) {
            if (!shutdown) {
                tasks.add(command)
            }
        }

        fun awaitQueuedTask() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (tasks.isEmpty()) {
                if (System.nanoTime() > deadline) {
                    error("Timed out waiting for executor task")
                }
                Thread.sleep(10)
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

    @AfterTest
    fun tearDown() {
        executors.forEach { it.shutdownNow() }
        executors.clear()
    }

    private fun createQueue(
        bufferDir: File? = null,
        config: PostHogConfig = PostHogConfig(API_KEY),
    ): PostHogReplayBufferQueue {
        val dir = bufferDir ?: File(tmpDir.newFolder(), "buffer")
        return PostHogReplayBufferQueue(config, dir)
    }

    private fun createExecutor(): ExecutorService {
        val executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "PostHogReplayBufferQueueTest").apply {
                    isDaemon = true
                }
            }
        executors.add(executor)
        return executor
    }

    private fun createPausedExecutor(): PausedExecutorService {
        val executor = PausedExecutorService()
        executors.add(executor)
        return executor
    }

    private fun awaitExecutors() {
        executors
            .filterNot { it is PausedExecutorService }
            .forEach { it.submit {}.get(2, TimeUnit.SECONDS) }
    }

    private fun createTargetQueue(
        config: PostHogConfig,
        storagePrefix: String,
        executor: ExecutorService = createExecutor(),
    ): PostHogQueue {
        return PostHogQueue(
            config,
            PostHogApi(config),
            PostHogApiEndpoint.SNAPSHOT,
            storagePrefix,
            executor,
        )
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

    private fun createEvent(name: String): PostHogEvent {
        return PostHogEvent(
            event = name,
            distinctId = "test-user",
            properties = mutableMapOf("name" to name),
            uuid = UUID.randomUUID(),
        )
    }

    @Test
    fun `add increases depth`() {
        val queue = createQueue()

        assertEquals(0, queue.depth)

        queue.add(createEvent("item1"))
        assertEquals(1, queue.depth)

        queue.add(createEvent("item2"))
        assertEquals(2, queue.depth)
    }

    @Test
    fun `clear resets depth to zero`() {
        val queue = createQueue()

        queue.add(createEvent("item1"))
        queue.add(createEvent("item2"))
        assertEquals(2, queue.depth)

        queue.clear()
        assertEquals(0, queue.depth)
    }

    @Test
    fun `init clears leftover buffer from previous session`() {
        val bufferDir = File(tmpDir.newFolder(), "buffer")
        bufferDir.mkdirs()

        File(bufferDir, "leftover.event").writeText("old data")
        assertTrue(bufferDir.listFiles()!!.isNotEmpty())

        val queue = createQueue(bufferDir)
        assertEquals(0, queue.depth)
        assertTrue(bufferDir.exists())
        assertTrue(bufferDir.listFiles()!!.isEmpty())
    }

    @Test
    fun `bufferDurationMs returns null for empty queue`() {
        val queue = createQueue()
        assertNull(queue.bufferDurationMs)
    }

    @Test
    fun `bufferDurationMs returns zero for single item`() {
        val queue = createQueue()
        queue.add(createEvent("only"))
        assertEquals(0L, queue.bufferDurationMs)
    }

    @Test
    fun `bufferDurationMs increases as items are added over time`() {
        val queue = createQueue()

        queue.add(createEvent("first"))
        val duration1 = queue.bufferDurationMs ?: 0

        Thread.sleep(50)
        queue.add(createEvent("second"))
        val duration2 = queue.bufferDurationMs ?: 0

        assertTrue(duration2 > duration1)
    }

    @Test
    fun `migrateAllTo noops when target queue is not PostHogQueue`() {
        val queue = createQueue()
        val targetQueue = FakeQueue()

        queue.add(createEvent("item1"))
        val migrated = queue.migrateAllTo(targetQueue)

        assertEquals(0, migrated)
        assertEquals(1, queue.depth)
        assertEquals(0, targetQueue.events.size)
    }

    @Test
    fun `migrateAllTo noops for non PostHogQueue target when empty`() {
        val queue = createQueue()
        val targetQueue = FakeQueue()

        val migrated = queue.migrateAllTo(targetQueue)

        assertEquals(0, migrated)
        assertEquals(0, queue.depth)
        assertEquals(0, targetQueue.events.size)
    }

    @Test
    fun `migrateAllTo moves files into PostHogQueue target in order`() {
        val config = PostHogConfig(API_KEY)
        val bufferDir = File(tmpDir.newFolder(), "buffer")
        val targetStoragePrefix = tmpDir.newFolder().absolutePath
        val targetQueue = createTargetQueue(config, targetStoragePrefix)
        val queue = createQueue(bufferDir = bufferDir, config = config)

        queue.add(createEvent("snapshot_1"))
        Thread.sleep(10)
        queue.add(createEvent("snapshot_2"))
        Thread.sleep(10)
        queue.add(createEvent("snapshot_3"))
        val sourceFileNames = bufferDir.listFiles()!!.map { it.name }.toSet()

        val migrated = queue.migrateAllTo(targetQueue)

        val targetDir = File(targetStoragePrefix, API_KEY)
        assertEquals(3, migrated)
        assertEquals(0, queue.depth)
        assertTrue(bufferDir.listFiles()!!.isEmpty())
        assertEquals(sourceFileNames, targetDir.listFiles()!!.map { it.name }.toSet())
        assertEquals(
            listOf("snapshot_1", "snapshot_2", "snapshot_3"),
            eventNamesInDirectory(config, targetDir),
        )

        targetQueue.clear()
        awaitExecutors()
        assertTrue(targetDir.listFiles()!!.isEmpty())
    }

    @Test
    fun `migrateAllTo leaves writes added while reload is pending in buffer`() {
        val config = PostHogConfig(API_KEY)
        val bufferDir = File(tmpDir.newFolder(), "buffer")
        val targetStoragePrefix = tmpDir.newFolder().absolutePath
        val targetExecutor = createPausedExecutor()
        val targetQueue = createTargetQueue(config, targetStoragePrefix, targetExecutor)
        val queue = createQueue(bufferDir = bufferDir, config = config)
        val migratedCount = AtomicInteger(-1)
        val migrationError = AtomicReference<Throwable?>()

        queue.add(createEvent("snapshot_before_1"))
        Thread.sleep(10)
        queue.add(createEvent("snapshot_before_2"))

        val migrationThread =
            Thread {
                try {
                    migratedCount.set(queue.migrateAllTo(targetQueue))
                } catch (e: Throwable) {
                    migrationError.set(e)
                }
            }.apply { start() }
        targetExecutor.awaitQueuedTask()

        queue.add(createEvent("snapshot_during_migration"))
        assertEquals(1, queue.depth)

        targetExecutor.runNext()
        migrationThread.join(2_000)

        assertFalse(migrationThread.isAlive)
        migrationError.get()?.let { throw AssertionError("Migration failed", it) }
        val targetDir = File(targetStoragePrefix, API_KEY)
        assertEquals(2, migratedCount.get())
        assertEquals(
            listOf("snapshot_before_1", "snapshot_before_2"),
            eventNamesInDirectory(config, targetDir),
        )
        assertEquals(
            listOf("snapshot_during_migration"),
            eventNamesInDirectory(config, bufferDir),
        )
        assertEquals(1, queue.depth)
    }

    @Test
    fun `migrateAllTo preserves existing target items in order`() {
        val config = PostHogConfig(API_KEY)
        val bufferDir = File(tmpDir.newFolder(), "buffer")
        val targetStoragePrefix = tmpDir.newFolder().absolutePath
        val targetQueue = createTargetQueue(config, targetStoragePrefix)
        val queue = createQueue(bufferDir = bufferDir, config = config)

        targetQueue.add(createEvent("target_old"))
        awaitExecutors()
        Thread.sleep(10)
        queue.add(createEvent("buffer_1"))
        Thread.sleep(10)
        queue.add(createEvent("buffer_2"))
        Thread.sleep(10)
        queue.add(createEvent("buffer_3"))

        val migrated = queue.migrateAllTo(targetQueue)

        val targetDir = File(targetStoragePrefix, API_KEY)
        assertEquals(3, migrated)
        assertEquals(0, queue.depth)
        assertEquals(4, targetDir.listFiles()!!.size)
        assertEquals(
            listOf("target_old", "buffer_1", "buffer_2", "buffer_3"),
            eventNamesInDirectory(config, targetDir),
        )
    }

    @Test
    fun `migrateAllTo discards duplicate filename without overwriting target`() {
        val config = PostHogConfig(API_KEY)
        val bufferDir = File(tmpDir.newFolder(), "buffer")
        val targetStoragePrefix = tmpDir.newFolder().absolutePath
        val targetQueue = createTargetQueue(config, targetStoragePrefix)
        val queue = createQueue(bufferDir = bufferDir, config = config)

        queue.add(createEvent("buffer_duplicate"))
        val bufferFile = bufferDir.listFiles()!!.single()
        val targetDir = File(targetStoragePrefix, API_KEY).apply { mkdirs() }
        File(targetDir, bufferFile.name).outputStream().use { output ->
            config.serializer.serialize(createEvent("target_existing"), output.writer().buffered())
        }

        val migrated = queue.migrateAllTo(targetQueue)

        assertEquals(0, migrated)
        assertEquals(0, queue.depth)
        assertTrue(bufferDir.listFiles()!!.isEmpty())
        assertEquals(listOf("target_existing"), eventNamesInDirectory(config, targetDir))
    }

    @Test
    fun `concurrent adds from multiple threads do not corrupt buffer`() {
        val queue = createQueue()
        val threadCount = 4
        val itemsPerThread = 20
        val startLatch = CountDownLatch(1)
        val threads =
            (0 until threadCount).map { threadIndex ->
                Thread {
                    startLatch.await()
                    for (itemIndex in 0 until itemsPerThread) {
                        queue.add(createEvent("thread_${threadIndex}_item_$itemIndex"))
                    }
                }.apply { start() }
            }

        startLatch.countDown()
        threads.forEach { it.join(2_000) }

        assertTrue(threads.none { it.isAlive })
        assertEquals(threadCount * itemsPerThread, queue.depth)
        assertNotNull(queue.bufferDurationMs)
        assertTrue(queue.bufferDurationMs!! >= 0)
    }

    @Test
    fun `timestampFromUUIDv7 extracts correct milliseconds`() {
        val filename = "019711e4-7c00-7000-8000-000000000000.event"
        val ts = PostHogReplayBufferQueue.timestampFromUUIDv7(filename)
        assertNotNull(ts)
        assertEquals(1748351876096L, ts)
    }

    @Test
    fun `timestampFromUUIDv7 returns null for invalid filename`() {
        assertNull(PostHogReplayBufferQueue.timestampFromUUIDv7("not-a-uuid.event"))
        assertNull(PostHogReplayBufferQueue.timestampFromUUIDv7(""))
    }
}
