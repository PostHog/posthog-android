package com.posthog.internal

import com.posthog.PROJECT_TOKEN
import com.posthog.PostHogConfig
import com.posthog.TestLogger
import com.posthog.awaitExecution
import com.posthog.shutdownAndAwaitTermination
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The byte limits of [EndpointSpec] as [PostHogQueue] applies them, on a synthetic record whose
 * stored size is its payload length.
 */
internal class PostHogQueueByteLimitsTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("Test"))
    private val logger = TestLogger()
    private val sent = mutableListOf<List<String>>()

    @Volatile
    private var connected = true

    private lateinit var config: PostHogConfig
    private lateinit var storagePrefix: String

    @AfterTest
    fun cleanup() {
        executor.shutdownAndAwaitTermination()
    }

    private fun record(
        id: String,
        bytes: Int,
    ): String = id.padEnd(bytes, 'x')

    private fun queue(
        maxBatchBytes: Long? = null,
        maxQueueBytes: Long? = null,
        flushAt: Int = 50,
        maxQueueSize: Int = 1000,
    ): PostHogQueue<String> {
        storagePrefix = tmpDir.newFolder().absolutePath
        config =
            PostHogConfig(PROJECT_TOKEN).apply {
                logger = this@PostHogQueueByteLimitsTest.logger
                networkStatus =
                    object : PostHogNetworkStatus {
                        override fun isConnected(): Boolean = connected
                    }
            }
        val spec =
            EndpointSpec(
                recordsLabel = "blobs",
                storagePrefix = storagePrefix,
                initialCap = { 50 },
                initialFlushAt = { flushAt },
                maxQueueSize = { maxQueueSize },
                flushIntervalSeconds = { 30 },
                encode = { record, stream -> stream.writer().use { it.write(record) } },
                decode = { stream -> stream.reader().use { it.readText() } },
                describe = { record -> "blob ${record.trimEnd('x')}" },
                send = { records ->
                    sent.add(records.map { it.trimEnd('x') })
                    SendResult()
                },
                isRetriableStatusCode = { false },
                maxBatchBytes = maxBatchBytes,
                maxQueueBytes = maxQueueBytes,
            )
        return PostHogQueue(config, spec, executor)
    }

    private fun queuedIds(sut: PostHogQueue<String>): List<String> = sut.dequeList.map { it.readText().trimEnd('x') }

    @Test
    fun `a flush splits the queue into requests at the byte limit`() {
        val sut = queue(maxBatchBytes = 100)
        connected = false
        sut.add(record("a", 40))
        sut.add(record("b", 40))
        sut.add(record("c", 40))
        executor.awaitExecution()
        connected = true

        sut.flush()
        executor.awaitExecution()

        assertEquals(listOf(listOf("a", "b"), listOf("c")), sent)
        assertTrue(sut.dequeList.isEmpty())
        assertEquals(0L, sut.queuedBytesForTesting)
    }

    @Test
    fun `a queued file deleted from disk is subtracted with the size it was counted with`() {
        val sut = queue(maxBatchBytes = 100)
        connected = false
        sut.add(record("a", 40))
        sut.add(record("b", 40))
        executor.awaitExecution()
        sut.dequeList.first().delete()
        connected = true

        sut.flush()
        executor.awaitExecution()

        assertEquals(listOf(listOf("b")), sent)
        assertEquals(0L, sut.queuedBytesForTesting)
    }

    @Test
    fun `addMeasured drops the oldest waiting records once those waiting to be written are over the limits`() {
        val sut = queue(maxQueueBytes = 100, maxQueueSize = 3)
        val release = CountDownLatch(1)
        executor.execute { release.await() }

        assertTrue(sut.addMeasured(record("a", 40), 40))
        assertTrue(sut.addMeasured(record("b", 40), 40))
        // 120 bytes waiting: a is dropped
        assertTrue(sut.addMeasured(record("c", 40), 40))
        assertTrue(sut.addMeasured(record("d", 20), 20))
        // 4 records waiting: b is dropped
        assertTrue(sut.addMeasured(record("e", 1), 1))
        // dropped while waiting, before the disk limits ever see them
        assertEquals(2, logger.messages.count { it.contains("blobs are waiting to be written, the oldest 1 are dropped") })
        release.countDown()
        executor.awaitExecution()

        assertEquals(listOf("c", "d", "e"), queuedIds(sut))
    }

    @Test
    fun `a record over the byte limit is sent alone and keeps its place`() {
        val sut = queue(maxBatchBytes = 100)
        connected = false
        sut.add(record("a", 40))
        sut.add(record("big", 150))
        sut.add(record("c", 40))
        executor.awaitExecution()
        connected = true

        sut.flush()
        executor.awaitExecution()

        assertEquals(listOf(listOf("a"), listOf("big"), listOf("c")), sent)
    }

    @Test
    fun `flushes once a request's worth of bytes is queued, before flushAt`() {
        val sut = queue(maxBatchBytes = 100, flushAt = 50)

        sut.add(record("a", 60))
        executor.awaitExecution()
        assertTrue(sent.isEmpty())

        sut.add(record("b", 60))
        executor.awaitExecution()

        // one request's worth: a alone, since b doesn't fit next to it
        assertEquals(listOf(listOf("a")), sent)
        assertEquals(listOf("b"), queuedIds(sut))
    }

    @Test
    fun `evicts the oldest records over maxQueueBytes when a record is added`() {
        val sut = queue(maxQueueBytes = 100, flushAt = 50)

        sut.add(record("a", 40))
        sut.add(record("b", 40))
        sut.add(record("c", 40))
        executor.awaitExecution()
        assertEquals(listOf("b", "c"), queuedIds(sut))

        // the newest record is kept even when it's over the limit on its own
        sut.add(record("big", 150))
        executor.awaitExecution()
        assertEquals(listOf("big"), queuedIds(sut))
        assertEquals(sut.dequeList.single().length(), sut.queuedBytesForTesting)
        assertEquals(1, File(storagePrefix, PROJECT_TOKEN).listFiles()!!.size)
        assertTrue(logger.messages.any { it == "Queue is over 100 bytes, the oldest 1 blobs are dropped." })
    }

    @Test
    fun `evicts the oldest cached records over maxQueueBytes on load`() {
        val sut = queue(maxQueueBytes = 100, flushAt = 50)
        val dir = File(storagePrefix, PROJECT_TOKEN).apply { mkdirs() }
        val cached =
            listOf("a", "b", "c").mapIndexed { index, id ->
                File(dir, "${UUID.randomUUID()}.event").apply {
                    writeText(record(id, 40))
                    setLastModified(System.currentTimeMillis() - (3 - index) * 1000L)
                }
            }

        sut.reloadFromDisk()

        assertEquals(cached.drop(1), sut.dequeList)
        assertEquals(listOf("b", "c"), queuedIds(sut))
        assertEquals(2, dir.listFiles()!!.size)
    }

    @Test
    fun `the analytics spec caps a request at 5 MiB and the AI spec adds the disk cap`() {
        val config = PostHogConfig(PROJECT_TOKEN)
        val api = PostHogApi(config)

        val batch = EndpointSpec.batch(config, api, null)
        assertEquals(5L * 1024 * 1024, batch.maxBatchBytes)
        assertEquals(null, batch.maxQueueBytes)

        val ai = EndpointSpec.ai(config, api, null)
        assertEquals(5L * 1024 * 1024, ai.maxBatchBytes)
        assertEquals(50L * 1024 * 1024, ai.maxQueueBytes)
        assertEquals(100, ai.maxQueueSize(config))
    }

    @Test
    fun `the AI event limit can only be lowered and non-positive settings use the defaults`() {
        val config = PostHogConfig(PROJECT_TOKEN)
        val api = PostHogApi(config)

        config.captureAiMaxEventBytes = 1024
        assertEquals(1024L, config.captureAiMaxStoredEventBytes)
        config.captureAiMaxEventBytes = 16 * 1024 * 1024
        assertEquals(8L * 1024 * 1024 + 64 * 1024, config.captureAiMaxStoredEventBytes)
        config.captureAiMaxEventBytes = 0
        assertEquals(8L * 1024 * 1024 + 64 * 1024, config.captureAiMaxStoredEventBytes)

        config.captureAiMaxQueueSize = 0
        assertEquals(100, EndpointSpec.ai(config, api, null).maxQueueSize(config))
        config.captureAiMaxQueueSize = 7
        assertEquals(7, EndpointSpec.ai(config, api, null).maxQueueSize(config))

        config.captureAiTimeout = -1
        assertEquals(60, config.effectiveCaptureAiTimeoutSeconds)
        config.captureAiTimeout = 5
        assertEquals(5, config.effectiveCaptureAiTimeoutSeconds)
    }
}
