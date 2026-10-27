package com.posthog

import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogThreadFactory
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogCaptureAiTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    @get:Rule
    val httpServers = TestHttpServers()

    private val queueExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestQueue"))
    private val replayQueueExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestReplayQueue"))
    private val logsExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestLogsQueue"))
    private val aiExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestAiQueue"))
    private val remoteConfigExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestRemoteConfig"))
    private val cachedEventsExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestCachedEvents"))
    private val logger = TestLogger()
    private val clients = mutableListOf<PostHogInterface>()
    private lateinit var config: PostHogConfig

    private fun getSut(
        http: MockWebServer,
        configure: PostHogConfig.() -> Unit = {},
    ): PostHogInterface {
        val storage = tmpDir.newFolder().absolutePath
        config =
            PostHogConfig(PROJECT_TOKEN, http.url("/").toString()).apply {
                storagePrefix = File(storage, "events").absolutePath
                replayStoragePrefix = File(storage, "snapshots").absolutePath
                logsStoragePrefix = File(storage, "logs").absolutePath
                aiStoragePrefix = File(storage, "ai").absolutePath
                flushAt = 1
                preloadFeatureFlags = false
                sendFeatureFlagEvent = false
                cachePreferences = PostHogMemoryPreferences()
                context = TestPostHogContext()
                logger = this@PostHogCaptureAiTest.logger
                configure()
            }
        return PostHog.withInternal(
            config,
            queueExecutor,
            replayQueueExecutor,
            remoteConfigExecutor,
            cachedEventsExecutor,
            reloadFeatureFlags = false,
            logsExecutor = logsExecutor,
            aiExecutor = aiExecutor,
        ).also { clients.add(it) }
    }

    @AfterTest
    fun cleanup() {
        clients.forEach { it.close() }
        listOf(queueExecutor, replayQueueExecutor, logsExecutor, aiExecutor, remoteConfigExecutor, cachedEventsExecutor).forEach {
            it.shutdownAndAwaitTermination()
        }
        tmpDir.root.deleteRecursively()
    }

    private fun aiQueueFiles(): List<File> = File(config.aiStoragePrefix!!, PROJECT_TOKEN).listFiles()?.toList() ?: emptyList()

    private fun takeRequest(http: MockWebServer): RecordedRequest = assertNotNull(http.takeRequest(5, TimeUnit.SECONDS))

    private fun events(request: RecordedRequest): List<Map<String, Any?>> = captureV1Events(request.body.unGzip())

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.properties(): Map<String, Any?> = this["properties"] as Map<String, Any?>

    @Test
    fun `captureAi posts to the AI endpoint and returns the uuid on the wire`() {
        val http = httpServers.mockHttp()
        val sut = getSut(http)

        val uuid = sut.captureAi("\$ai_generation", properties = mapOf("\$ai_model" to "gpt-5"))

        aiExecutor.awaitExecution()
        val request = takeRequest(http)
        assertEquals("/i/v1/ai/events", request.path)
        assertEquals("Bearer $PROJECT_TOKEN", request.headers["Authorization"])
        assertEquals("1", request.headers["PostHog-Attempt"])
        val event = events(request).single()
        assertEquals("\$ai_generation", event["event"])
        assertEquals(assertNotNull(uuid).toString(), event["uuid"])
        assertEquals(sut.distinctId(), event["distinct_id"])
        // same enrichment as capture: SDK context and the caller's properties
        assertEquals("gpt-5", event.properties()["\$ai_model"])
        assertEquals("Android", event.properties()["\$os_name"])
        queueExecutor.awaitExecution()
        assertEquals(1, http.requestCount)
    }

    @Test
    fun `capture keeps an ai-named event on the analytics endpoint`() {
        val http = httpServers.mockHttp()
        val sut = getSut(http)

        sut.capture("\$ai_generation")

        queueExecutor.awaitExecution()
        aiExecutor.awaitExecution()
        assertEquals("/i/v1/analytics/events", takeRequest(http).path)
        assertEquals(1, http.requestCount)
        assertTrue(aiQueueFiles().isEmpty())
    }

    @Test
    fun `beforeSend, options and the personless default apply to AI events`() {
        val http = httpServers.mockHttp()
        val sut =
            getSut(http) {
                addBeforeSend { event ->
                    event.properties?.remove("\$ai_input")
                    event.properties?.put("redacted", true)
                    event
                }
            }

        sut.captureAi(
            "\$ai_generation",
            properties = mapOf("\$ai_input" to "secret prompt"),
            options = mapOf("cookieless_mode" to true),
        )

        aiExecutor.awaitExecution()
        val event = events(takeRequest(http)).single()
        assertEquals(true, event.properties()["redacted"])
        assertTrue("\$ai_input" !in event.properties())
        // identified-only without an identify: the SDK adds the personless option
        assertEquals(mapOf("cookieless_mode" to true, "process_person_profile" to false), event["options"])
    }

    @Test
    fun `captureAi returns null when disabled, opted out or rejected by beforeSend`() {
        val http = httpServers.mockHttp()
        val sut = getSut(http) { addBeforeSend { event -> if (event.event == "\$ai_rejected") null else event } }

        assertNull(sut.captureAi("\$ai_rejected"))
        sut.optOut()
        assertNull(sut.captureAi("\$ai_generation"))
        sut.optIn()
        sut.close()
        assertNull(sut.captureAi("\$ai_generation"))

        aiExecutor.awaitExecution()
        assertEquals(0, http.requestCount)
        assertTrue(aiQueueFiles().isEmpty())
    }

    @Test
    fun `a 404 from the AI endpoint is terminal`() {
        val http = httpServers.mockHttp(total = 2, response = MockResponse().setResponseCode(404))
        val sut = getSut(http)

        sut.captureAi("\$ai_generation")
        aiExecutor.awaitExecution()
        sut.flush()
        aiExecutor.awaitExecution()

        assertEquals(1, http.requestCount)
        assertEquals("/i/v1/ai/events", takeRequest(http).path)
        assertTrue(aiQueueFiles().isEmpty())
    }

    @Test
    fun `an AI event over captureAiMaxEventBytes returns null and logs only its name and size`() {
        val http = httpServers.mockHttp()
        // the limit is the configured bytes plus a 64 KiB envelope
        val sut = getSut(http) { captureAiMaxEventBytes = 1 }
        val prompt = "p".repeat(70_000)

        val uuid = sut.captureAi("\$ai_generation", properties = mapOf("\$ai_input" to prompt))

        assertNull(uuid)
        aiExecutor.awaitExecution()
        sut.flush()
        aiExecutor.awaitExecution()
        assertEquals(0, http.requestCount)
        assertTrue(aiQueueFiles().isEmpty())
        val dropped = logger.messages.single { it.startsWith("Dropping AI event") }
        assertTrue(
            dropped.matches(Regex("Dropping AI event \\\$ai_generation of \\d+ bytes, over the ${1 + 64 * 1024} byte limit\\.")),
            dropped,
        )
        assertTrue(logger.messages.none { it.contains("pppp") })
    }

    @Test
    fun `the AI event limit is measured before encryption`() {
        val http = httpServers.mockHttp()
        val sut =
            getSut(http) {
                captureAiMaxEventBytes = 1
                // Base64 stores a third more bytes than it's given
                encryption =
                    object : PostHogEncryption {
                        override fun encrypt(outputStream: OutputStream): OutputStream = Base64.getEncoder().wrap(outputStream)

                        override fun decrypt(inputStream: InputStream): InputStream = Base64.getDecoder().wrap(inputStream)
                    }
            }
        // under the 64 KiB + 1 byte limit as JSON, over it once encrypted
        val prompt = "p".repeat(60_000)

        val uuid = sut.captureAi("\$ai_generation", properties = mapOf("\$ai_input" to prompt))

        assertNotNull(uuid)
        aiExecutor.awaitExecution()
        assertEquals(uuid.toString(), events(takeRequest(http)).single()["uuid"])
    }

    @Test
    fun `a name that doesn't start with ai is logged and sent unchanged`() {
        val http = httpServers.mockHttp()
        val sut = getSut(http)

        sut.captureAi("not_ai")

        aiExecutor.awaitExecution()
        val request = takeRequest(http)
        assertEquals("/i/v1/ai/events", request.path)
        assertEquals("not_ai", events(request).single()["event"])
        assertTrue(logger.messages.any { it.startsWith("captureAi event not_ai doesn't start with \$ai_") })
    }

    @Test
    fun `flush and close drain the AI queue`() {
        val http = httpServers.mockHttp(total = 2)
        val sut = getSut(http) { flushAt = 10 }

        sut.captureAi("\$ai_generation")
        aiExecutor.awaitExecution()
        assertEquals(0, http.requestCount)

        sut.flush()
        aiExecutor.awaitExecution()
        assertEquals(1, http.requestCount)

        sut.captureAi("\$ai_span")
        aiExecutor.awaitExecution()
        sut.close()
        aiExecutor.awaitExecution()
        assertEquals(2, http.requestCount)
        assertEquals(listOf("/i/v1/ai/events", "/i/v1/ai/events"), List(2) { takeRequest(http).path })
    }

    @Test
    fun `compression is set per lane`() {
        val http = httpServers.mockHttp(total = 2)
        val sut =
            getSut(http) {
                compression = PostHogCompression.NONE
                captureAiCompression = PostHogCompression.GZIP
            }

        sut.capture("event")
        sut.captureAi("\$ai_generation")

        queueExecutor.awaitExecution()
        aiExecutor.awaitExecution()
        val requests = List(2) { takeRequest(http) }.associateBy { it.path }
        val analytics = assertNotNull(requests["/i/v1/analytics/events"])
        val ai = assertNotNull(requests["/i/v1/ai/events"])
        assertNull(analytics.headers["Content-Encoding"])
        assertTrue(analytics.body.readUtf8().contains("\"event\":\"event\""))
        assertEquals("gzip", ai.headers["Content-Encoding"])
        assertEquals("\$ai_generation", events(ai).single()["event"])
    }

    @Test
    fun `captureAiCompression off sends the AI request uncompressed while analytics stay gzipped`() {
        val http = httpServers.mockHttp(total = 2)
        val sut = getSut(http) { captureAiCompression = PostHogCompression.NONE }

        sut.capture("event")
        sut.captureAi("\$ai_generation")

        queueExecutor.awaitExecution()
        aiExecutor.awaitExecution()
        val requests = List(2) { takeRequest(http) }.associateBy { it.path }
        assertEquals("gzip", assertNotNull(requests["/i/v1/analytics/events"]).headers["Content-Encoding"])
        val ai = assertNotNull(requests["/i/v1/ai/events"])
        assertNull(ai.headers["Content-Encoding"])
        assertEquals("\$ai_generation", captureV1Events(ai.body.readUtf8()).single()["event"])
    }

    @Test
    fun `AI requests time out after captureAiTimeout and keep the event for retry`() {
        val http = httpServers.mockHttp(response = MockResponse().setHeadersDelay(3, TimeUnit.SECONDS))
        val sut = getSut(http) { captureAiTimeout = 1 }

        sut.captureAi("\$ai_generation")
        aiExecutor.awaitExecution()

        assertEquals(1, http.requestCount)
        assertEquals(1, aiQueueFiles().size)
        assertTrue(logger.messages.any { it.contains("network error") })
    }
}
