package com.posthog.server.logback

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.posthog.server.PostHog
import com.posthog.server.PostHogConfig
import com.posthog.server.PostHogInterface
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.GzipSource
import okio.buffer
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

internal class PostHogAppenderTest {
    private val gson = Gson()
    private var mockServer: MockWebServer? = null
    private var postHog: PostHogInterface? = null
    private var loggerContext: LoggerContext? = null

    @AfterTest
    fun tearDown() {
        loggerContext?.stop()
        postHog?.close()
        PostHogAppender.clearPostHog()
        mockServer?.shutdown()
    }

    /**
     * Wires a programmatic Logback context with a started [PostHogAppender] attached to a fresh
     * logger, backed by a real server client pointing at a [MockWebServer] (flushAt=1 so each event
     * is flushed on the queue thread right after being enqueued).
     */
    private fun setup(
        minimumCaptureLevel: Level = Level.ERROR,
        loggerName: String = "com.example.Service",
        register: Boolean = true,
    ): Logger {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()
        mockServer = server

        val client =
            PostHog.with(
                PostHogConfig.builder("test-api-key")
                    .host(server.url("/").toString())
                    .flushAt(1)
                    .build(),
            )
        postHog = client
        if (register) {
            PostHogAppender.setPostHog(client)
        }

        val context = LoggerContext()
        loggerContext = context
        val appender =
            PostHogAppender().apply {
                this.context = context
                this.minimumCaptureLevel = minimumCaptureLevel
                start()
            }

        val logger = context.getLogger(loggerName)
        logger.level = Level.TRACE
        logger.addAppender(appender)
        logger.isAdditive = false
        return logger
    }

    private fun takeBatch(): BatchRequest? {
        val request = mockServer?.takeRequest(5, TimeUnit.SECONDS) ?: return null
        return BatchRequest(request, gson)
    }

    @Test
    fun `error with throwable is captured as one exception event`() {
        val logger = setup()

        logger.error("payment failed", IllegalStateException("kaboom"))

        val batch = takeBatch()
        assertNotNull(batch, "Expected a /batch request")
        val event = batch.findEvent("\$exception")
        assertNotNull(event, "Expected one \$exception event")

        val props = batch.eventProperties("\$exception")
        assertEquals("error", props["\$exception_level"])
        assertEquals("com.example.Service", props["logger_name"])
        // Message differs from the throwable's message, so it is attached.
        assertEquals("payment failed", props["log_message"])
    }

    @Test
    fun `below threshold events are ignored`() {
        val logger = setup(minimumCaptureLevel = Level.ERROR)

        logger.warn("just a warning", IllegalStateException("ignored"))

        // No capture ⇒ no /batch request within the window.
        assertNull(mockServer?.takeRequest(2, TimeUnit.SECONDS), "WARN below ERROR threshold must be ignored")
    }

    @Test
    fun `warn is captured with warning level when threshold lowered`() {
        val logger = setup(minimumCaptureLevel = Level.WARN)

        logger.warn("degraded", RuntimeException("slow"))

        val batch = takeBatch()
        assertNotNull(batch)
        val props = batch.eventProperties("\$exception")
        assertEquals("warning", props["\$exception_level"], "WARN maps to \"warning\" when captured")
    }

    @Test
    fun `posthog logger names are ignored to prevent recursion`() {
        setup(loggerName = "com.posthog.internal.Something")
        val logger = loggerContext!!.getLogger("com.posthog.internal.Something")

        logger.error("SDK internal error", IllegalStateException("loop"))

        assertNull(mockServer?.takeRequest(2, TimeUnit.SECONDS), "com.posthog.* loggers must be ignored")
    }

    @Test
    fun `logger packages merely sharing the string prefix are still captured`() {
        setup(loggerName = "com.posthogger.Service")
        val logger = loggerContext!!.getLogger("com.posthogger.Service")

        logger.error("app error", IllegalStateException("not the SDK"))

        val batch = takeBatch()
        assertNotNull(batch, "com.posthogger.* is not an SDK logger and must be captured")
        assertEquals("com.posthogger.Service", batch.eventProperties("\$exception")["logger_name"])
    }

    @Test
    fun `events without a throwable are ignored`() {
        val logger = setup()

        logger.error("no throwable here")

        assertNull(mockServer?.takeRequest(2, TimeUnit.SECONDS), "Message-only events must be ignored in v1")
    }

    @Test
    fun `events before a client is registered are dropped`() {
        val logger = setup(register = false)

        logger.error("early", IllegalStateException("too soon"))

        assertNull(mockServer?.takeRequest(2, TimeUnit.SECONDS), "Events before registration must be dropped")
    }

    @Test
    fun `the same throwable logged twice is captured once`() {
        val logger = setup()
        val boom = IllegalStateException("only once")

        logger.error("first", boom)
        logger.error("second", boom)

        val first = takeBatch()
        assertNotNull(first, "Expected the first capture")
        assertNotNull(first.findEvent("\$exception"))

        // The second log of the identical instance is deduped ⇒ no further request.
        assertNull(mockServer?.takeRequest(2, TimeUnit.SECONDS), "The same throwable instance must be captured once")
    }

    @Test
    fun `each self-configured appender sends through its own client`() {
        // Two appenders configured for different projects must not cross-route: whoever started last
        // owns the process-wide fallback slot, but each appender captures through its own client.
        val firstServer = MockWebServer()
        firstServer.enqueue(MockResponse().setResponseCode(200))
        firstServer.start()
        val secondServer = MockWebServer()
        secondServer.enqueue(MockResponse().setResponseCode(200))
        secondServer.start()

        val context = LoggerContext()
        loggerContext = context

        fun appenderFor(server: MockWebServer) =
            PostHogAppender().apply {
                this.context = context
                this.apiKey = "test-api-key"
                this.host = server.url("/").toString()
                start()
            }

        val first = appenderFor(firstServer)
        val second = appenderFor(secondServer)

        val logger = context.getLogger("com.example.Routed")
        logger.level = Level.TRACE
        logger.addAppender(first)
        logger.isAdditive = false

        logger.error("routed", IllegalStateException("first project"))

        // Self-configured clients use the SDK's default buffering, so wait out one flush interval.
        val request = firstServer.takeRequest(15, TimeUnit.SECONDS)
        assertNotNull(request, "The event must go to the appender's own client")
        assertNotNull(BatchRequest(request, gson).findEvent("\$exception"))
        assertNull(secondServer.takeRequest(2, TimeUnit.SECONDS), "The other project must receive nothing")

        first.stop()
        second.stop()
        firstServer.shutdown()
        secondServer.shutdown()
    }

    @Test
    fun `a self-configured appender keeps capturing after an earlier one stops`() {
        // Logback config reload order: the replacement appender starts before the old one stops. Each
        // self-configuring appender must own its own client, or the old one's stop() would take the
        // shared client down and silently mute the replacement.
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()
        mockServer = server

        val context = LoggerContext()
        loggerContext = context

        fun selfConfiguredAppender() =
            PostHogAppender().apply {
                this.context = context
                this.apiKey = "test-api-key"
                this.host = server.url("/").toString()
                start()
            }

        val old = selfConfiguredAppender()
        val replacement = selfConfiguredAppender()
        old.stop()

        val logger = context.getLogger("com.example.Reloaded")
        logger.level = Level.TRACE
        logger.addAppender(replacement)
        logger.isAdditive = false

        logger.error("after reload", IllegalStateException("still captured"))

        // Self-configured clients use the SDK's default buffering, so wait out one flush interval
        // instead of relying on flushAt=1 like the other tests.
        val request = server.takeRequest(15, TimeUnit.SECONDS)
        assertNotNull(request, "The replacement appender must still capture after the old one stops")
        assertNotNull(BatchRequest(request, gson).findEvent("\$exception"))

        replacement.stop()
    }

    /** Minimal gzip + JSON batch parser (the server module's test utils are not on this classpath). */
    private class BatchRequest(request: RecordedRequest, private val gson: Gson) {
        private val batch: List<JsonObject>

        init {
            val body =
                GzipSource(request.body).use { source ->
                    source.buffer().use { it.readUtf8() }
                }
            val json = gson.fromJson(body, JsonObject::class.java)
            batch = json.getAsJsonArray("batch")?.map { it.asJsonObject } ?: emptyList()
        }

        fun findEvent(eventName: String): JsonObject? = batch.find { it.get("event")?.asString == eventName }

        fun eventProperties(eventName: String): Map<String, Any?> {
            val props = findEvent(eventName)?.getAsJsonObject("properties") ?: return emptyMap()
            return gson.fromJson(props, object : TypeToken<Map<String, Any?>>() {}.type)
        }
    }
}
