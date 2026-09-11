package com.posthog.internal

import com.posthog.API_KEY
import com.posthog.PostHogConfig
import com.posthog.awaitExecution
import com.posthog.shutdownAndAwaitTermination
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class PostHogQueueDurabilityTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("DurabilityTest"))
    private val queues = mutableListOf<PostHogQueue<String>>()
    private val clock = RetryClock()

    @After
    fun tearDown() {
        queues.forEach {
            it.stop()
            it.clear()
        }
        executor.shutdownAndAwaitTermination()
    }

    private fun queue(
        capacity: Int = 3,
        batchSize: Int = 3,
        path: String = tmpDir.newFolder().absolutePath,
        networkStatus: PostHogNetworkStatus? = null,
        encode: (String) -> ByteArray = { it.toByteArray() },
        send: (List<String>) -> Unit = {},
    ): PostHogQueue<String> {
        val config =
            PostHogConfig(API_KEY).apply {
                maxQueueSize = capacity
                maxBatchSize = batchSize
                flushAt = 100
                maxRetries = 2
                dateProvider = clock
                this.networkStatus = networkStatus
            }
        val spec =
            EndpointSpec(
                recordsLabel = "records",
                storagePrefix = path,
                initialCap = { it.maxBatchSize },
                initialFlushAt = { it.flushAt },
                maxQueueSize = { it.maxQueueSize },
                flushIntervalSeconds = { it.flushIntervalSeconds },
                encode = { record, stream -> stream.write(encode(record)) },
                decode = { stream ->
                    String(stream.readBytes()).also {
                        if (it == "corrupt") throw IOException("corrupt record")
                    }
                },
                describe = { it },
                send = send,
                isRetriableStatusCode = ::isEventsRetriableStatusCode,
            )
        return PostHogQueue(config, spec, executor).also { queues.add(it) }
    }

    @Test
    fun `failed enqueue at capacity preserves existing durable entries`() {
        val sut =
            queue(capacity = 2, encode = {
                if (it == "unwritable") throw IOException("encoding failed")
                it.toByteArray()
            })
        sut.add("first")
        sut.add("second")
        executor.awaitExecution()
        val originalFiles = sut.dequeList

        sut.add("unwritable")
        executor.awaitExecution()

        assertEquals(originalFiles, sut.dequeList)
        assertEquals(listOf("first", "second"), originalFiles.map { it.readText() })
        assertEquals(originalFiles.toSet(), sut.queueDirectory!!.listFiles()!!.toSet())
    }

    @Test
    fun `successful enqueue at capacity evicts only oldest durable entry`() {
        val sut = queue(capacity = 2)
        sut.add("first")
        sut.add("second")
        executor.awaitExecution()
        val originalFiles = sut.dequeList

        sut.add("third")
        executor.awaitExecution()

        assertFalse(originalFiles.first().exists())
        assertEquals(originalFiles.last(), sut.dequeList.first())
        assertEquals(listOf("second", "third"), sut.dequeList.map { it.readText() })
        assertEquals(sut.dequeList.toSet(), sut.queueDirectory!!.listFiles()!!.toSet())
    }

    @Test
    fun `retryable failures retain exact entries with capped backoff then reset after success`() {
        val failures = listOf(408, 429, 500, 502, 503, 504)
        var status = failures.first()
        var attempts = 0
        val sut =
            queue(send = {
                attempts++
                if (status != 200) throw PostHogApiError(status, "retry", null)
            })
        sut.add("retained")
        executor.awaitExecution()
        val originalFiles = sut.dequeList

        // Continue well beyond maxRetries and the retry counter's saturation point.
        repeat(35) { index ->
            status = failures[index % failures.size]
            sut.flush()
            executor.awaitExecution()
            assertEquals(index + 1, attempts)
            assertEquals(originalFiles, sut.dequeList)
            assertEquals("retained", originalFiles.single().readText())
            assertEquals(minOf(index + 1, 30), sut.currentRetryCountForTesting)
            sut.flush()
            executor.awaitExecution()
            assertEquals(index + 1, attempts, "Explicit flush must respect cooldown")
            clock.advanceToRetry()
        }
        assertEquals(listOf(1, 2, 4, 8, 16) + List(30) { 30 }, clock.delays)

        status = 200
        sut.flush()
        executor.awaitExecution()
        assertEquals(36, attempts)
        assertEquals(0, sut.currentRetryCountForTesting)
        assertTrue(sut.dequeList.isEmpty())
        assertFalse(originalFiles.single().exists())

        sut.add("fresh")
        status = 503
        sut.flush()
        executor.awaitExecution()
        assertEquals(37, attempts)
        assertEquals(1, sut.currentRetryCountForTesting)
        assertEquals(1, clock.delays.last())
        assertEquals("fresh", sut.dequeList.single().readText())
    }

    @Test
    fun `network recovery respects existing cooldown without consuming attempts offline`() {
        var connected = true
        var onAvailable: (() -> Unit)? = null
        var attempts = 0
        val networkStatus =
            object : PostHogNetworkStatus {
                override fun isConnected() = connected

                override fun register(callback: () -> Unit) {
                    onAvailable = callback
                }
            }
        val sut =
            queue(networkStatus = networkStatus, send = {
                if (++attempts == 1) throw IOException("connection reset")
            })
        sut.start()
        sut.add("retained")
        sut.flush()
        executor.awaitExecution()
        connected = false
        repeat(3) {
            sut.flush()
            executor.awaitExecution()
        }
        assertEquals(1, attempts)
        assertEquals(1, sut.currentRetryCountForTesting)
        assertEquals(listOf(1), clock.delays)
        connected = true
        onAvailable!!.invoke()
        executor.awaitExecution()
        assertEquals(1, attempts)
        clock.advanceToRetry()
        onAvailable!!.invoke()
        executor.awaitExecution()
        assertEquals(2, attempts)
        assertEquals(0, sut.currentRetryCountForTesting)
        assertTrue(sut.dequeList.isEmpty())
    }

    @Test
    fun `413 reaches singleton poison record and delivers later entries after retry failures`() {
        var transient = true
        val batches = mutableListOf<List<String>>()
        val sut =
            queue(capacity = 4, batchSize = 4, send = { records ->
                batches.add(records)
                if (transient) throw IOException("connection reset")
                if ("poison" in records) throw PostHogApiError(413, "too large", null)
            })
        listOf("poison", "second", "third", "fourth").forEach { sut.add(it) }
        executor.awaitExecution()
        val originalFiles = sut.dequeList
        repeat(3) {
            sut.flush()
            executor.awaitExecution()
            clock.advanceToRetry()
            assertEquals(originalFiles, sut.dequeList)
        }
        transient = false
        repeat(2) { index ->
            sut.flush()
            executor.awaitExecution()
            assertEquals(if (index == 0) 2 else 1, sut.currentBatchCapForTesting)
            assertEquals(originalFiles, sut.dequeList)
            clock.advanceToRetry()
        }
        sut.flush()
        executor.awaitExecution()

        assertEquals(listOf(4, 4, 4, 4, 2, 1, 1, 1, 1), batches.map { it.size })
        assertEquals(listOf(listOf("poison"), listOf("second"), listOf("third"), listOf("fourth")), batches.takeLast(4))
        assertTrue(sut.dequeList.isEmpty())
        assertTrue(originalFiles.none { it.exists() })
        assertEquals(0, sut.currentRetryCountForTesting)
    }

    @Test
    fun `terminal response deletes only sent snapshot and retains later retryable entries`() {
        var attempts = 0
        val batches = mutableListOf<List<String>>()
        val sut =
            queue(batchSize = 1, send = {
                batches.add(it)
                when (++attempts) {
                    1 -> throw PostHogApiError(400, "invalid record", null)
                    2 -> throw PostHogApiError(503, "retry later", null)
                }
            })
        listOf("terminal", "retained", "later").forEach { sut.add(it) }
        executor.awaitExecution()
        val files = sut.dequeList
        sut.flush()
        executor.awaitExecution()
        assertFalse(files.first().exists())
        assertEquals(files.drop(1), sut.dequeList)
        assertEquals(listOf("retained", "later"), sut.dequeList.map { it.readText() })
        clock.advanceToRetry()
        sut.flush()
        executor.awaitExecution()
        assertEquals(listOf("terminal", "retained", "retained", "later"), batches.flatten())
        assertTrue(sut.dequeList.isEmpty())
    }

    @Test
    fun `restart retains retry files enforces FIFO capacity and isolates corrupt record`() {
        val path = tmpDir.newFolder().absolutePath
        val original = queue(capacity = 4, path = path, send = { throw IOException("offline") })
        listOf("oldest", "second", "third", "fourth").forEach { original.add(it) }
        executor.awaitExecution()
        val files = original.dequeList
        repeat(3) {
            original.flush()
            executor.awaitExecution()
            clock.advanceToRetry()
            assertEquals(files, original.dequeList)
        }
        files[1].writeText("corrupt")
        files.forEachIndexed { index, file -> assertTrue(file.setLastModified(1000L * (index + 1))) }
        // Stop does not clear pending durable writes or entries.
        original.stop()
        val sent = mutableListOf<String>()
        val restarted = queue(capacity = 3, path = path, send = { sent.addAll(it) })
        restarted.reloadFromDisk()
        assertEquals(files.drop(1), restarted.dequeList)
        assertFalse(files.first().exists())
        restarted.flush()
        executor.awaitExecution()
        assertEquals(listOf("third", "fourth"), sent)
        assertTrue(restarted.dequeList.isEmpty())
        assertTrue(files.none { it.exists() })
    }

    @Test
    fun `identical records added during a supported serial flush are delivered later`() {
        val sendStarted = CountDownLatch(1)
        val releaseSend = CountDownLatch(1)
        val batches = mutableListOf<List<String>>()
        val sut =
            queue(send = {
                batches.add(it)
                if (batches.size == 1) {
                    sendStarted.countDown()
                    check(releaseSend.await(5, TimeUnit.SECONDS))
                }
            })
        repeat(3) { sut.add("same") }
        executor.awaitExecution()
        val originalFiles = sut.dequeList
        try {
            sut.flush()
            assertTrue(sendStarted.await(5, TimeUnit.SECONDS))
            repeat(3) { sut.add("same") }
        } finally {
            releaseSend.countDown()
        }
        executor.awaitExecution()
        assertEquals(listOf(List(3) { "same" }), batches)
        assertEquals(3, sut.dequeList.size)
        assertTrue(sut.dequeList.none { it in originalFiles })
        assertTrue(originalFiles.none { it.exists() })
        assertEquals(List(3) { "same" }, sut.dequeList.map { it.readText() })
        sut.flush()
        executor.awaitExecution()
        assertEquals(List(2) { List(3) { "same" } }, batches)
        assertTrue(sut.dequeList.isEmpty())
    }

    private class RetryClock : PostHogDateProvider {
        private var now = 0L
        private var retryAt = 0L
        val delays = mutableListOf<Int>()

        fun advanceToRetry() {
            now = retryAt
        }

        override fun currentDate() = Date(now)

        override fun addSecondsToCurrentDate(seconds: Int): Date {
            delays.add(seconds)
            retryAt = now + seconds * 1000L
            return Date(retryAt)
        }

        override fun currentTimeMillis() = now

        override fun nanoTime() = now * 1_000_000
    }
}
