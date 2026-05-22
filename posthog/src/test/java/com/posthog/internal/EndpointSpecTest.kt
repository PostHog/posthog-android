package com.posthog.internal

import com.posthog.API_KEY
import com.posthog.PostHogConfig
import com.posthog.TestPostHogContext
import com.posthog.awaitExecution
import com.posthog.generateEvent
import com.posthog.mockHttp
import com.posthog.shutdownAndAwaitTermination
import com.posthog.unGzip
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
            recordsLabel = "synthetic",
            storagePrefix = storagePrefix,
            initialCap = { 50 },
            initialFlushAt = { initialFlushAt },
            maxQueueSize = { 1000 },
            flushIntervalSeconds = { 30 },
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
    fun `spec maxQueueSize lambda is re-read from config on each add`() {
        // Locks in the lambda-vs-snapshot contract: spec config knobs are
        // (PostHogConfig) -> Int lambdas so runtime mutations to config
        // are honored. If maxQueueSize were snapshotted at construction,
        // raising config.maxQueueSize after setup would have no effect
        // and the deque would still cap at the original value.
        val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("Test"))
        val storagePrefix = tmpDir.newFolder().absolutePath
        val config =
            PostHogConfig(API_KEY, "http://localhost:9999").apply {
                this.storagePrefix = storagePrefix
                this.maxQueueSize = 2
                this.flushAt = 1000 // never auto-flush
            }
        val api = PostHogApi(config)
        val queue = PostHogQueue(config, EndpointSpec.batch(config, api, storagePrefix), executor)

        queue.add(generateEvent("a"))
        queue.add(generateEvent("b"))
        executor.awaitExecution()
        assertEquals(2, queue.dequeList.size)

        // Raise the cap at runtime.
        config.maxQueueSize = 5

        queue.add(generateEvent("c"))
        queue.add(generateEvent("d"))
        queue.add(generateEvent("e"))
        executor.shutdownAndAwaitTermination()

        // All 5 records retained — proves the spec re-reads config.maxQueueSize
        // each time removeRecordSync runs. A snapshot would still cap at 2.
        assertEquals(5, queue.dequeList.size)
    }

    @Test
    fun `EndpointSpec logs factory writes to slash i v1 logs`() {
        val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("Test"))
        val storagePrefix = tmpDir.newFolder().absolutePath
        val http = mockHttp(response = MockResponse().setBody(""))
        val config =
            PostHogConfig(API_KEY, http.url("/").toString()).apply {
                this.storagePrefix = storagePrefix
                // EndpointSpec.logs reads its flushAt from logs.flushAt, not the
                // top-level events flushAt — set the logs config field, not the
                // events one.
                this.logs.flushAt = 1
            }
        val api = PostHogApi(config)
        val spec = EndpointSpec.logs(config, api, storagePrefix)
        val queue = PostHogQueue(config, spec, executor)

        queue.add(com.posthog.logs.PostHogLogRecord(body = "via-spec"))

        executor.shutdownAndAwaitTermination()

        assertEquals(1, http.requestCount)
        val request = http.takeRequest()
        assertTrue(request.path!!.startsWith("/i/v1/logs?token="))
        // Without the body assertion the test would pass if the queue posted an
        // empty body or a non-OTLP body — prove the encoded record actually
        // reaches the wire.
        val unzipped = request.body.unGzip()
        assertTrue(unzipped.contains("\"resourceLogs\""))
        assertTrue(unzipped.contains("\"stringValue\":\"via-spec\""))

        http.shutdown()
    }

    @Test
    fun `logs spec retry policy retries 408 429 and 5xx`() {
        assertTrue(isLogsRetriableStatusCode(408))
        assertTrue(isLogsRetriableStatusCode(429))
        assertTrue(isLogsRetriableStatusCode(500))
        assertTrue(isLogsRetriableStatusCode(599))
        // 3xx redirects are not retriable — a redirect from /i/v1/logs means
        // misconfigured host, retrying would loop.
        assertEquals(false, isLogsRetriableStatusCode(301))
        assertEquals(false, isLogsRetriableStatusCode(400))
        assertEquals(false, isLogsRetriableStatusCode(404))
        assertEquals(false, isLogsRetriableStatusCode(600))
    }

    @Test
    fun `logs factory wires os name and version from PostHogContext into resource attributes`() {
        // TestPostHogContext supplies $os_name = "Android" and $os_version = "13".
        // The logs factory should translate those into OTLP os.name / os.version
        // on the wire so log payloads carry device context without needing
        // PostHogLogsConfig (lands in the public-capture PR).
        val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("Test"))
        val storagePrefix = tmpDir.newFolder().absolutePath
        val http = mockHttp(response = MockResponse().setBody(""))
        val config =
            PostHogConfig(API_KEY, http.url("/").toString()).apply {
                this.storagePrefix = storagePrefix
                this.flushAt = 1
                this.context = TestPostHogContext()
            }
        val api = PostHogApi(config)
        val spec = EndpointSpec.logs(config, api, storagePrefix)
        val queue = PostHogQueue(config, spec, executor)

        queue.add(com.posthog.logs.PostHogLogRecord(body = "ctx"))

        executor.shutdownAndAwaitTermination()

        val unzipped = http.takeRequest().body.unGzip()
        assertTrue(unzipped.contains("\"key\":\"os.name\""), "os.name missing: $unzipped")
        assertTrue(unzipped.contains("\"stringValue\":\"Android\""), "os.name=Android missing: $unzipped")
        assertTrue(unzipped.contains("\"key\":\"os.version\""), "os.version missing: $unzipped")
        assertTrue(unzipped.contains("\"stringValue\":\"13\""), "os.version=13 missing: $unzipped")

        http.shutdown()
    }

    @Test
    fun `logs factory omits os attributes when PostHogContext is null`() {
        // Pure-JVM consumers without the Android overlay have config.context == null.
        // The factory must not crash and must not emit os.* keys.
        val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("Test"))
        val storagePrefix = tmpDir.newFolder().absolutePath
        val http = mockHttp(response = MockResponse().setBody(""))
        val config =
            PostHogConfig(API_KEY, http.url("/").toString()).apply {
                this.storagePrefix = storagePrefix
                this.flushAt = 1
                // context intentionally not set (null)
            }
        val api = PostHogApi(config)
        val spec = EndpointSpec.logs(config, api, storagePrefix)
        val queue = PostHogQueue(config, spec, executor)

        queue.add(com.posthog.logs.PostHogLogRecord(body = "no-ctx"))

        executor.shutdownAndAwaitTermination()

        val unzipped = http.takeRequest().body.unGzip()
        assertEquals(false, unzipped.contains("\"os.name\""), "os.name should be absent: $unzipped")
        assertEquals(false, unzipped.contains("\"os.version\""), "os.version should be absent: $unzipped")

        http.shutdown()
    }

    @Test
    fun `events spec retry policy stays narrower than logs`() {
        // Locks in the asymmetry. If someone copy-pastes the logs predicate
        // (408 + all 5xx) into the events code path, 408 and 501/505/etc.
        // would silently start retrying for events.
        assertTrue(isEventsRetriableStatusCode(429))
        assertTrue(isEventsRetriableStatusCode(500))
        assertTrue(isEventsRetriableStatusCode(502))
        assertTrue(isEventsRetriableStatusCode(503))
        assertTrue(isEventsRetriableStatusCode(504))
        assertEquals(false, isEventsRetriableStatusCode(408))
        assertEquals(false, isEventsRetriableStatusCode(501))
        assertEquals(false, isEventsRetriableStatusCode(505))
        assertEquals(false, isEventsRetriableStatusCode(599))
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
