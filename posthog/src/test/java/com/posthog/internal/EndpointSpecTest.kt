package com.posthog.internal

import com.posthog.API_KEY
import com.posthog.PostHogConfig
import com.posthog.awaitExecution
import com.posthog.generateEvent
import com.posthog.mockHttp
import com.posthog.shutdownAndAwaitTermination
import okhttp3.mockwebserver.MockResponse
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the generic [PostHogQueue] + [EndpointSpec] abstraction actually
 * works for a non-[com.posthog.PostHogEvent] record type. Without these tests,
 * we have no evidence the genericization is real vs. a no-op rename.
 *
 * Behavior coverage for events is in [PostHogQueueTest] — re-running it
 * unchanged proves the events code path still works after the refactor.
 */
internal class EndpointSpecTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private data class SyntheticRecord(val value: String)

    private class CapturingSender {
        val batches = mutableListOf<List<SyntheticRecord>>()
        var thrown: Throwable? = null

        fun send(records: List<SyntheticRecord>) {
            batches.add(records)
            thrown?.let { throw it }
        }
    }

    private fun syntheticSpec(
        storagePrefix: String,
        sender: CapturingSender = CapturingSender(),
        isRetriableStatusCode: (Int) -> Boolean = { false },
        encodeFails: Boolean = false,
        initialFlushAt: Int = 50,
    ): EndpointSpec<SyntheticRecord> =
        EndpointSpec(
            name = "synthetic",
            storagePrefix = storagePrefix,
            initialCap = 50,
            initialFlushAt = initialFlushAt,
            maxQueueSize = 1000,
            flushIntervalSeconds = 30,
            encode = { record, stream ->
                if (encodeFails) throw RuntimeException("encode failed")
                stream.writer().buffered().use { it.write(record.value) }
            },
            decode = { stream ->
                stream.reader().buffered().use { SyntheticRecord(it.readText()) }
            },
            describe = { "synthetic '${it.value}'" },
            send = { records -> sender.send(records) },
            isRetriableStatusCode = isRetriableStatusCode,
        )

    @Test
    fun `add persists the record encoded by spec encode`() {
        val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("Test"))
        val storagePrefix = tmpDir.newFolder().absolutePath
        val config = PostHogConfig(API_KEY)
        val spec = syntheticSpec(storagePrefix)
        val queue = PostHogQueue(config, spec, executor)

        queue.add(SyntheticRecord("hello"))

        executor.awaitExecution()

        val files = File(storagePrefix, API_KEY).listFiles()!!
        assertEquals(1, files.size)
        assertEquals("hello", files[0].readText())

        executor.shutdownAndAwaitTermination()
    }

    @Test
    fun `flush invokes spec send with decoded records and pops files`() {
        val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("Test"))
        val storagePrefix = tmpDir.newFolder().absolutePath
        val config = PostHogConfig(API_KEY)
        val sender = CapturingSender()
        val spec = syntheticSpec(storagePrefix, sender = sender)
        val queue = PostHogQueue(config, spec, executor)

        // flushAt=1 in the spec triggers a flush on add — but with no
        // network there's no API to mock. Manually push, then flush.
        queue.add(SyntheticRecord("a"))
        queue.add(SyntheticRecord("b"))
        queue.flush()

        executor.shutdownAndAwaitTermination()

        assertTrue(sender.batches.isNotEmpty())
        val flat = sender.batches.flatten().map { it.value }
        assertEquals(listOf("a", "b"), flat)
        assertEquals(0, File(storagePrefix, API_KEY).listFiles()!!.size)
    }

    @Test
    fun `spec isRetriableStatusCode retains files on retriable error`() {
        val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("Test"))
        val storagePrefix = tmpDir.newFolder().absolutePath
        val config = PostHogConfig(API_KEY)
        val sender = CapturingSender()
        // 999 is non-default, only retriable per this spec
        val spec =
            syntheticSpec(
                storagePrefix,
                sender = sender,
                isRetriableStatusCode = { code -> code == 999 },
            )
        sender.thrown = PostHogApiError(999, "custom retriable", null)
        val queue = PostHogQueue(config, spec, executor)

        queue.add(SyntheticRecord("a"))
        queue.flush()

        executor.awaitExecution()

        // file retained because 999 is retriable per spec
        assertEquals(1, File(storagePrefix, API_KEY).listFiles()!!.size)

        executor.shutdownAndAwaitTermination()
    }

    @Test
    fun `spec isRetriableStatusCode deletes files on non-retriable error`() {
        val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("Test"))
        val storagePrefix = tmpDir.newFolder().absolutePath
        val config = PostHogConfig(API_KEY)
        val sender = CapturingSender()
        // 999 is non-retriable per this spec (default returns false)
        val spec = syntheticSpec(storagePrefix, sender = sender)
        sender.thrown = PostHogApiError(999, "non-retriable", null)
        val queue = PostHogQueue(config, spec, executor)

        queue.add(SyntheticRecord("a"))
        queue.flush()

        executor.awaitExecution()

        // file dropped because 999 is not retriable per spec
        assertEquals(0, File(storagePrefix, API_KEY).listFiles()!!.size)

        executor.shutdownAndAwaitTermination()
    }

    @Test
    fun `encode failure deletes the file and does not crash`() {
        val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("Test"))
        val storagePrefix = tmpDir.newFolder().absolutePath
        val config = PostHogConfig(API_KEY)
        val spec = syntheticSpec(storagePrefix, encodeFails = true)
        val queue = PostHogQueue(config, spec, executor)

        queue.add(SyntheticRecord("bad"))

        executor.shutdownAndAwaitTermination()

        assertEquals(0, File(storagePrefix, API_KEY).listFiles()!!.size)
    }

    @Test
    fun `EndpointSpec batch factory preserves events on-wire behavior`() {
        val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("Test"))
        val storagePrefix = tmpDir.newFolder().absolutePath
        val http = mockHttp(response = MockResponse().setBody(""))
        val config =
            PostHogConfig(API_KEY, http.url("/").toString()).apply {
                this.storagePrefix = storagePrefix
                this.flushAt = 1
            }
        val api = PostHogApi(config)
        val spec = EndpointSpec.batch(config, api, storagePrefix)
        val queue = PostHogQueue(config, spec, executor)

        queue.add(generateEvent("event-via-spec"))

        executor.shutdownAndAwaitTermination()

        assertEquals(1, http.requestCount)
        val request = http.takeRequest()
        assertTrue(request.path!!.startsWith("/batch"))

        http.shutdown()
    }
}
