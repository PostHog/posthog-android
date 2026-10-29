package com.posthog.internal

import com.posthog.BuildConfig
import com.posthog.PROJECT_TOKEN
import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.TestHttpServers
import com.posthog.TestLogger
import com.posthog.awaitExecution
import com.posthog.date
import com.posthog.shutdownAndAwaitTermination
import com.posthog.unGzip
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogCaptureV1Test {
    @get:Rule
    val httpServers = TestHttpServers()

    @get:Rule
    val tmpDir = TemporaryFolder()

    private val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("CaptureV1Test"))
    private val queues = mutableListOf<PostHogQueue<PostHogEvent>>()
    private val logger = TestLogger()
    private val clock = FakePostHogDateProvider()
    private val serializer = PostHogSerializer(PostHogConfig(PROJECT_TOKEN))

    @AfterTest
    fun cleanup() {
        queues.forEach {
            it.stop()
            it.clear()
        }
        executor.shutdownAndAwaitTermination()
    }

    private fun config(host: String): PostHogConfig =
        PostHogConfig(PROJECT_TOKEN, host).apply {
            logger = this@PostHogCaptureV1Test.logger
            dateProvider = clock
        }

    private fun api(host: String): PostHogApi = PostHogApi(config(host))

    private fun queue(
        http: MockWebServer,
        flushAt: Int = 1,
        storagePrefix: String = tmpDir.newFolder().absolutePath,
        ai: Boolean = false,
    ): PostHogQueue<PostHogEvent> {
        val config =
            config(http.url("/").toString()).apply {
                this.flushAt = flushAt
                this.storagePrefix = storagePrefix
            }
        // pause time pinned to the past so a backoff never blocks the next flush
        clock.setAddSecondsToCurrentDate(parseISO8601Date("1970-01-01T00:00:00.000Z")!!)
        val api = PostHogApi(config)
        val spec = if (ai) EndpointSpec.ai(config, api, config.storagePrefix) else EndpointSpec.batch(config, api, config.storagePrefix)
        return PostHogQueue(config, spec, executor).also { queues.add(it) }
    }

    private fun resultsResponse(vararg results: Pair<UUID, String>): MockResponse {
        val entries = results.joinToString(",") { (uuid, result) -> "\"$uuid\":{\"result\":\"$result\"}" }
        return MockResponse().setBody("{\"results\":{$entries}}")
    }

    private fun event(
        name: String = "event",
        properties: Map<String, Any> = mapOf("prop" to "value"),
        options: Map<String, Any?>? = null,
    ): PostHogEvent =
        PostHogEvent(
            name,
            distinctId = "distinctId",
            properties = properties.toMutableMap(),
            timestamp = date,
            uuid = UUID.randomUUID(),
            options = options?.toMutableMap(),
        )

    @Suppress("UNCHECKED_CAST")
    private fun body(request: RecordedRequest): Map<String, Any?> =
        serializer.deserialize<Map<String, Any?>>(request.body.unGzip().reader())

    @Suppress("UNCHECKED_CAST")
    private fun batch(request: RecordedRequest): List<Map<String, Any?>> = body(request)["batch"] as List<Map<String, Any?>>

    private fun wire(event: PostHogEvent): Map<String, Any?> = captureV1Json(event, logger)

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.properties(): Map<String, Any?> = this["properties"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.options(): Map<String, Any?>? = this["options"] as Map<String, Any?>?

    @Test
    fun `posts to the capture v1 endpoint with the required headers`() {
        val http = httpServers.mockHttp(response = resultsResponse())
        clock.setCurrentDate(parseISO8601Date("2024-05-01T10:20:30.456Z")!!)

        api(http.url("/").toString()).captureV1(listOf(event()))

        val request = http.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/i/v1/analytics/events", request.path)
        assertEquals("Bearer $PROJECT_TOKEN", request.headers["Authorization"])
        assertEquals("posthog-java/${BuildConfig.VERSION_NAME}", request.headers["PostHog-Sdk-Info"])
        assertEquals("posthog-java/${BuildConfig.VERSION_NAME}", request.headers["User-Agent"])
        assertEquals("1", request.headers["PostHog-Attempt"])
        UUID.fromString(assertNotNull(request.headers["PostHog-Request-Id"]))
        assertEquals("2024-05-01T10:20:30.456Z", request.headers["PostHog-Request-Timestamp"])
        assertEquals("application/json", request.headers["Content-Type"])
        assertEquals("gzip", request.headers["Content-Encoding"])
    }

    @Test
    fun `captureAi posts to the AI endpoint with the same headers and body shape`() {
        val http = httpServers.mockHttp(response = resultsResponse())
        clock.setCurrentDate(parseISO8601Date("2024-05-01T10:20:30.456Z")!!)
        val event = event("\$ai_generation")

        api(http.url("/").toString()).captureAi(listOf(event))

        val request = http.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/i/v1/ai/events", request.path)
        assertEquals("Bearer $PROJECT_TOKEN", request.headers["Authorization"])
        assertEquals("posthog-java/${BuildConfig.VERSION_NAME}", request.headers["PostHog-Sdk-Info"])
        assertEquals("1", request.headers["PostHog-Attempt"])
        UUID.fromString(assertNotNull(request.headers["PostHog-Request-Id"]))
        assertEquals("2024-05-01T10:20:30.456Z", request.headers["PostHog-Request-Timestamp"])
        assertEquals("application/json", request.headers["Content-Type"])
        assertEquals("gzip", request.headers["Content-Encoding"])
        val body = body(request)
        assertEquals(setOf("created_at", "batch"), body.keys)
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf(event.uuid.toString()), (body["batch"] as List<Map<String, Any?>>).map { it["uuid"] })
    }

    @Test
    fun `analytics and AI retries keep separate request identities`() {
        val analytics = event()
        val ai = event("\$ai_generation")
        val http = httpServers.mockHttp(response = resultsResponse(analytics.uuid!! to "retry"))
        http.enqueue(resultsResponse(ai.uuid!! to "retry"))
        http.enqueue(resultsResponse(analytics.uuid!! to "ok"))
        http.enqueue(resultsResponse(ai.uuid!! to "ok"))
        val sut = api(http.url("/").toString())

        sut.captureV1(listOf(analytics))
        sut.captureAi(listOf(ai))
        sut.captureV1(listOf(analytics))
        sut.captureAi(listOf(ai))

        val requests = List(4) { http.takeRequest() }
        assertEquals(
            listOf("/i/v1/analytics/events", "/i/v1/ai/events", "/i/v1/analytics/events", "/i/v1/ai/events"),
            requests.map { it.path },
        )
        assertEquals(listOf("1", "1", "2", "2"), requests.map { it.headers["PostHog-Attempt"] })
        val ids = requests.map { it.headers["PostHog-Request-Id"] }
        assertNotEquals(ids[0], ids[1])
        assertEquals(ids[0], ids[2])
        assertEquals(ids[1], ids[3])
    }

    @Test
    fun `the capture Bearer token replaces a custom Authorization header`() {
        val http = httpServers.mockHttp(response = resultsResponse())
        val config =
            config(http.url("/").toString()).apply {
                requestHeaders = mapOf("Authorization" to "Bearer proxy", "X-Proxy" to "yes")
            }

        PostHogApi(config).captureV1(listOf(event()))

        val request = http.takeRequest()
        assertEquals("Bearer $PROJECT_TOKEN", request.headers["Authorization"])
        assertEquals("yes", request.headers["X-Proxy"])
    }

    @Test
    fun `body carries created_at and the batch without api_key or sent_at`() {
        val http = httpServers.mockHttp(response = resultsResponse())
        clock.setCurrentDate(parseISO8601Date("2024-05-01T10:20:30.456Z")!!)
        val event = event()

        api(http.url("/").toString()).captureV1(listOf(event))

        val body = body(http.takeRequest())
        assertEquals(setOf("created_at", "batch"), body.keys)
        assertEquals("2024-05-01T10:20:30.456Z", body["created_at"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf(event.uuid.toString()), (body["batch"] as List<Map<String, Any?>>).map { it["uuid"] })
    }

    @Test
    fun `an event is sent with its root fields and an RFC 3339 UTC timestamp`() {
        val event = event(properties = mapOf("prop" to "value", "\$lib" to "posthog-android", "\$lib_version" to "1.2.3"))

        val sent = wire(event)

        assertEquals(listOf("event", "uuid", "distinct_id", "timestamp", "properties"), sent.keys.toList())
        assertEquals("event", sent["event"])
        assertEquals(event.uuid.toString(), sent["uuid"])
        assertEquals("distinctId", sent["distinct_id"])
        assertEquals("2023-09-20T11:58:49.000Z", sent["timestamp"])
        // $lib and $lib_version come from the PostHog-Sdk-Info header
        assertEquals(mapOf("prop" to "value"), sent.properties())
        assertNull(sent.options())
    }

    @Test
    fun `lifts string session and window ids to the event root`() {
        val sent = wire(event(properties = mapOf("\$session_id" to "session", "\$window_id" to "")))

        assertEquals("session", sent["session_id"])
        assertEquals("", sent["window_id"])
        assertFalse(sent.properties().containsKey("\$session_id"))
        assertFalse(sent.properties().containsKey("\$window_id"))
        assertTrue(logger.messages.isEmpty())
    }

    @Test
    fun `drops a non-string session id with a warning naming the key and JSON type`() {
        val sent = wire(event(properties = mapOf("\$session_id" to 42, "\$window_id" to listOf("a"))))

        assertFalse(sent.containsKey("session_id"))
        assertFalse(sent.containsKey("window_id"))
        assertFalse(sent.properties().containsKey("\$session_id"))
        assertFalse(sent.properties().containsKey("\$window_id"))
        assertEquals(
            listOf(
                "dropping \$session_id: a number value is not a string",
                "dropping \$window_id: a array value is not a string",
            ),
            logger.messages,
        )
    }

    @Test
    fun `drops a null session id silently`() {
        val event = event()
        @Suppress("UNCHECKED_CAST")
        (event.properties as MutableMap<String, Any?>)["\$session_id"] = null

        val sent = wire(event)

        assertFalse(sent.containsKey("session_id"))
        assertFalse(sent.properties().containsKey("\$session_id"))
        assertTrue(logger.messages.isEmpty())
    }

    @Test
    fun `hoists legacy option properties into options and always removes them`() {
        val event =
            event(
                properties =
                    mapOf(
                        "\$process_person_profile" to false,
                        "\$cookieless_mode" to "yes",
                        "\$ignore_sent_at" to true,
                        "\$product_tour_id" to 5,
                    ),
            )

        val sent = wire(event)

        // values are sent as given, the server reads them
        assertEquals(
            mapOf("cookieless_mode" to "yes", "disable_skew_correction" to true, "product_tour_id" to 5, "process_person_profile" to false),
            sent.options(),
        )
        assertEquals(mapOf<String, Any?>(), sent.properties())
    }

    @Test
    fun `an option set on the event wins over its legacy property`() {
        val event =
            event(
                properties = mapOf("\$process_person_profile" to false, "\$cookieless_mode" to false),
                options = mapOf("process_person_profile" to true, "cookieless_mode" to null, "custom" to "kept"),
            )

        val sent = wire(event)

        // a null option counts as unset, so the legacy property fills it; unknown keys pass through
        assertEquals(mapOf("process_person_profile" to true, "cookieless_mode" to false, "custom" to "kept"), sent.options())
        assertFalse(sent.properties().containsKey("\$process_person_profile"))
        assertFalse(sent.properties().containsKey("\$cookieless_mode"))
    }

    @Test
    fun `returns the uuids to retry and drops the rest from the batch`() {
        val ok = event("ok")
        val dropped = event("dropped")
        val retry = event("retry")
        val missing = event("missing")
        val http =
            httpServers.mockHttp(
                response =
                    resultsResponse(
                        ok.uuid!! to "ok",
                        dropped.uuid!! to "drop",
                        retry.uuid!! to "retry",
                    ).setHeader("Retry-After", "7"),
            )

        val result = api(http.url("/").toString()).captureV1(listOf(ok, dropped, retry, missing))

        assertEquals(setOf(retry.uuid.toString()), result.retryIds)
        assertEquals(7, result.retryAfterSeconds)
        assertTrue(logger.messages.contains("Capture: 1 dropped, 0 duplicate copies dropped, 1 to retry."))
    }

    @Test
    fun `logs one count line per batch, without event names, uuids or properties`() {
        val misrouted = event("button_clicked", properties = mapOf("prompt" to "secret prompt"))
        val tooBig = event("\$ai_generation", properties = mapOf("prompt" to "secret prompt"))
        val http =
            httpServers.mockHttp(
                response =
                    MockResponse().setBody(
                        "{\"results\":{" +
                            "\"${misrouted.uuid}\":{\"result\":\"drop\",\"details\":\"misrouted_event\"}," +
                            "\"${tooBig.uuid}\":{\"result\":\"drop\",\"details\":\"ai_event_too_big\"}}}",
                    ),
            )

        api(http.url("/").toString()).captureAi(listOf(misrouted, tooBig))

        assertEquals(
            listOf("Capture: 2 dropped, 0 duplicate copies dropped, 0 to retry."),
            logger.messages.filter { it.startsWith("Capture:") },
        )
        assertTrue(
            logger.messages.none {
                it.contains("secret prompt") || it.contains("button_clicked") ||
                    it.contains(misrouted.uuid.toString()) || it.contains(tooBig.uuid.toString())
            },
        )
    }

    @Test
    fun `counts the duplicate copies capture dropped`() {
        val kept = event()
        val copy = kept.copy()
        val http =
            httpServers.mockHttp(
                response = MockResponse().setBody("{\"results\":{\"${kept.uuid}\":{\"result\":\"ok\",\"duplicates_dropped\":1}}}"),
            )

        val result = api(http.url("/").toString()).captureV1(listOf(kept, copy))

        assertTrue(result.retryIds.isEmpty())
        assertTrue(logger.messages.contains("Capture: 0 dropped, 1 duplicate copies dropped, 0 to retry."))
    }

    @Test
    fun `clamps each duplicate count to the batch size`() {
        val first = event()
        val second = event()
        val http =
            httpServers.mockHttp(
                response =
                    MockResponse().setBody(
                        "{\"results\":{\"${first.uuid}\":{\"result\":\"ok\",\"duplicates_dropped\":99999999999999999999}," +
                            "\"${second.uuid}\":{\"result\":\"ok\",\"duplicates_dropped\":-5}}}",
                    ),
            )

        api(http.url("/").toString()).captureV1(listOf(first, second))

        assertTrue(logger.messages.contains("Capture: 0 dropped, 2 duplicate copies dropped, 0 to retry."))
    }

    @Test
    fun `a 2xx without readable results is a terminal failure`() {
        for (body in listOf("not json", "{}", "{\"results\":[]}")) {
            val http = httpServers.mockHttp(response = MockResponse().setBody(body))

            val error = assertThrows(PostHogApiError::class.java) { api(http.url("/").toString()).captureV1(listOf(event())) }

            assertEquals(200, error.statusCode)
            assertFalse(isCaptureV1RetriableStatusCode(error.statusCode))
        }
        assertTrue(logger.messages.any { it.contains("without readable per-event results, dropping 1 events") })
    }

    @Test
    fun `a 2xx whose transfer fails after the status is a terminal failure`() {
        val http =
            httpServers.mockHttp(
                response =
                    MockResponse()
                        .setBody("{\"results\":{\"some-uuid\":{\"result\":\"retry\"}}}")
                        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
            )

        assertThrows(PostHogApiError::class.java) { api(http.url("/").toString()).captureV1(listOf(event())) }
    }

    @Test
    fun `a 2xx with results over 1 MiB is a terminal failure`() {
        val event = event()
        val padding = "x".repeat(1024 * 1024)
        val http =
            httpServers.mockHttp(
                response = MockResponse().setBody("{\"results\":{\"${event.uuid}\":{\"result\":\"retry\"}},\"pad\":\"$padding\"}"),
            )

        assertThrows(PostHogApiError::class.java) { api(http.url("/").toString()).captureV1(listOf(event)) }
    }

    @Test
    fun `a 2xx whose results trickle past the read deadline is a terminal failure`() {
        val event = event()
        val padding = "x".repeat(16 * 1024)
        // throttles the request read too, so allow a whole small request per period
        val http =
            httpServers.mockHttp(
                response =
                    MockResponse()
                        .setBody("{\"results\":{\"${event.uuid}\":{\"result\":\"retry\"}},\"pad\":\"$padding\"}")
                        .throttleBody(1024, 200, TimeUnit.MILLISECONDS),
            )
        val sut = api(http.url("/").toString()).apply { captureV1ResultsReadDeadlineMs = 300 }

        assertThrows(PostHogApiError::class.java) { sut.captureV1(listOf(event)) }
    }

    @Test
    fun `a 404 warns once with the capture URL`() {
        val http = httpServers.mockHttp(total = 2, response = MockResponse().setResponseCode(404))
        val host = http.url("/").toString().trimEnd('/')
        val sut = api(host)

        repeat(2) { assertThrows(PostHogApiError::class.java) { sut.captureV1(listOf(event())) } }

        assertEquals(
            listOf(
                "$host/i/v1/analytics/events returned 404. Events stay queued and are retried. " +
                    "If you use a reverse proxy or self-host PostHog, make sure it serves /i/v1/analytics/events.",
            ),
            logger.warnings,
        )
    }

    @Test
    fun `the 404 warning leaves out credentials and the query in the host`() {
        val http = httpServers.mockHttp(response = MockResponse().setResponseCode(404))
        val origin = http.url("/").toString().trimEnd('/')
        val host = origin.replace("://", "://proxy-user:proxy-password@") + "?secret=s3cr3t"
        val sut = api(host)

        assertThrows(PostHogApiError::class.java) { sut.captureV1(listOf(event())) }

        val warning = logger.warnings.single()
        assertTrue(warning.startsWith("$origin/"), warning)
        listOf("proxy-user", "proxy-password", "s3cr3t").forEach { assertFalse(warning.contains(it), warning) }
    }

    @Test
    fun `a 503 with Retry-After 0 is not resent by the HTTP client`() {
        val http = httpServers.mockHttp(response = MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        http.enqueue(resultsResponse())

        assertThrows(PostHogApiError::class.java) { api(http.url("/").toString()).captureV1(listOf(event())) }

        assertEquals(1, http.requestCount)
    }

    @Test
    fun `a retry reuses the request id and created_at and increments the attempt`() {
        val event = event()
        val http = httpServers.mockHttp(response = resultsResponse(event.uuid!! to "retry"))
        http.enqueue(resultsResponse(event.uuid!! to "ok"))
        clock.setCurrentDate(parseISO8601Date("2024-05-01T10:20:30.000Z")!!)
        val sut = api(http.url("/").toString())

        sut.captureV1(listOf(event))
        clock.setCurrentDate(parseISO8601Date("2024-05-01T10:20:31.000Z")!!)
        sut.captureV1(listOf(event))

        val first = http.takeRequest()
        val second = http.takeRequest()
        assertEquals("1", first.headers["PostHog-Attempt"])
        assertEquals("2", second.headers["PostHog-Attempt"])
        assertEquals(first.headers["PostHog-Request-Id"], second.headers["PostHog-Request-Id"])
        assertEquals("2024-05-01T10:20:30.000Z", first.headers["PostHog-Request-Timestamp"])
        assertEquals("2024-05-01T10:20:31.000Z", second.headers["PostHog-Request-Timestamp"])
        assertEquals("2024-05-01T10:20:30.000Z", body(first)["created_at"])
        assertEquals("2024-05-01T10:20:30.000Z", body(second)["created_at"])
    }

    @Test
    fun `a retriable status and a network error keep the request identity`() {
        val http = httpServers.mockHttp(response = MockResponse().setResponseCode(503))
        http.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        http.enqueue(resultsResponse())
        val events = listOf(event())
        // a short read timeout turns the unanswered request into an IOException OkHttp doesn't retry itself
        val config =
            config(http.url("/").toString()).apply {
                httpClient = OkHttpClient.Builder().readTimeout(500, TimeUnit.MILLISECONDS).build()
            }
        val sut = PostHogApi(config)

        assertThrows(PostHogApiError::class.java) { sut.captureV1(events) }
        assertThrows(IOException::class.java) { sut.captureV1(events) }
        sut.captureV1(events)

        val requestIds = List(3) { http.takeRequest() }.map { it.headers["PostHog-Request-Id"] to it.headers["PostHog-Attempt"] }
        assertEquals(1, requestIds.map { it.first }.toSet().size)
        assertEquals(listOf("1", "2", "3"), requestIds.map { it.second })
    }

    @Test
    fun `a dropped connection is not resent by the HTTP client`() {
        val http = httpServers.mockHttp(response = resultsResponse())
        // the second request reuses the pooled connection, which OkHttp would otherwise retry on a new one
        http.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        http.enqueue(resultsResponse())
        val sut = api(http.url("/").toString())

        sut.captureV1(listOf(event()))
        assertThrows(IOException::class.java) { sut.captureV1(listOf(event())) }

        assertEquals(2, http.requestCount)
    }

    @Test
    fun `debug logs leave out event content`() {
        val sent = event(properties = mapOf("email" to "user@example.com"))
        val http = httpServers.mockHttp(response = resultsResponse(sent.uuid!! to "ok"))
        val config = config(http.url("/").toString()).apply { debug = true }

        PostHogApi(config).captureV1(listOf(sent))

        assertTrue(logger.messages.any { it.endsWith("/i/v1/analytics/events: 1 events") })
        assertTrue(logger.messages.none { it.contains("user@example.com") || it.contains(sent.uuid.toString()) })
    }

    @Test
    fun `a different set of events starts a new request identity`() {
        val event = event()
        val other = event("other")
        val http = httpServers.mockHttp(response = resultsResponse(event.uuid!! to "retry"))
        http.enqueue(resultsResponse(event.uuid!! to "ok", other.uuid!! to "ok"))
        val sut = api(http.url("/").toString())

        sut.captureV1(listOf(event))
        sut.captureV1(listOf(event, other))

        val first = http.takeRequest()
        val second = http.takeRequest()
        assertEquals("1", second.headers["PostHog-Attempt"])
        assertNotEquals(first.headers["PostHog-Request-Id"], second.headers["PostHog-Request-Id"])
    }

    @Test
    fun `sending other events in between keeps the pending retry identity`() {
        val retried = event()
        val other = event("other")
        val http = httpServers.mockHttp(response = resultsResponse(retried.uuid!! to "retry"))
        http.enqueue(resultsResponse(other.uuid!! to "ok"))
        http.enqueue(resultsResponse(retried.uuid!! to "ok"))
        val sut = api(http.url("/").toString())

        sut.captureV1(listOf(retried))
        sut.captureV1(listOf(other))
        sut.captureV1(listOf(retried))

        val (first, between, resent) = List(3) { http.takeRequest() }
        assertEquals("1", between.headers["PostHog-Attempt"])
        assertNotEquals(first.headers["PostHog-Request-Id"], between.headers["PostHog-Request-Id"])
        assertEquals("2", resent.headers["PostHog-Attempt"])
        assertEquals(first.headers["PostHog-Request-Id"], resent.headers["PostHog-Request-Id"])
        assertEquals(body(first)["created_at"], body(resent)["created_at"])
    }

    @Test
    fun `a terminal status starts a new request identity on the next send`() {
        val http = httpServers.mockHttp(response = MockResponse().setResponseCode(400))
        http.enqueue(resultsResponse())
        val events = listOf(event())
        val sut = api(http.url("/").toString())

        assertThrows(PostHogApiError::class.java) { sut.captureV1(events) }
        sut.captureV1(events)

        val first = http.takeRequest()
        val second = http.takeRequest()
        assertEquals("1", second.headers["PostHog-Attempt"])
        assertNotEquals(first.headers["PostHog-Request-Id"], second.headers["PostHog-Request-Id"])
    }

    @Test
    fun `a non-2xx throws with its status and Retry-After`() {
        for (status in listOf(408, 500, 502, 503, 504, 429, 404, 400, 413)) {
            val http = httpServers.mockHttp(response = MockResponse().setResponseCode(status).setHeader("Retry-After", "7"))

            val error = assertThrows(PostHogApiError::class.java) { api(http.url("/").toString()).captureV1(listOf(event())) }

            assertEquals(status, error.statusCode)
            assertEquals(7, error.retryAfterSeconds)
        }
    }

    @Test
    fun `follows a same-origin 307 with the same body and headers`() {
        val http = httpServers.mockHttp(response = MockResponse().setResponseCode(307).setHeader("Location", "/proxied/events"))
        http.enqueue(MockResponse().setResponseCode(308).setHeader("Location", "/final/events"))
        http.enqueue(resultsResponse())

        api(http.url("/").toString()).captureV1(listOf(event()))

        val requests = List(3) { http.takeRequest() }
        assertEquals(listOf("/i/v1/analytics/events", "/proxied/events", "/final/events"), requests.map { it.path })
        requests.forEach {
            assertEquals("POST", it.method)
            assertEquals("Bearer $PROJECT_TOKEN", it.headers["Authorization"])
            assertEquals(requests[0].headers["PostHog-Request-Id"], it.headers["PostHog-Request-Id"])
            assertEquals(requests[0].headers["PostHog-Request-Timestamp"], it.headers["PostHog-Request-Timestamp"])
        }
        assertEquals(1, requests.map { body(it) }.toSet().size)
    }

    @Test
    fun `does not follow a 302`() {
        val http = httpServers.mockHttp(response = MockResponse().setResponseCode(302).setHeader("Location", "/elsewhere"))
        http.enqueue(resultsResponse())

        val error = assertThrows(PostHogApiError::class.java) { api(http.url("/").toString()).captureV1(listOf(event())) }

        assertEquals(302, error.statusCode)
        assertEquals(1, http.requestCount)
    }

    @Test
    fun `does not follow a 307 to another origin`() {
        val other = httpServers.mockHttp(response = resultsResponse())
        val http =
            httpServers.mockHttp(
                response = MockResponse().setResponseCode(307).setHeader("Location", other.url("/events").toString()),
            )

        val error = assertThrows(PostHogApiError::class.java) { api(http.url("/").toString()).captureV1(listOf(event())) }

        assertEquals(307, error.statusCode)
        assertEquals(1, http.requestCount)
        assertEquals(0, other.requestCount)
        assertTrue(logger.messages.contains("Capture did not follow a 307 redirect."))
    }

    @Test
    fun `stops after five redirects`() {
        val http = httpServers.mockHttp(total = 6, response = MockResponse().setResponseCode(307).setHeader("Location", "/again"))
        http.enqueue(resultsResponse())

        val error = assertThrows(PostHogApiError::class.java) { api(http.url("/").toString()).captureV1(listOf(event())) }

        assertEquals(307, error.statusCode)
        assertEquals(6, http.requestCount)
    }

    @Test
    fun `queue keeps only the events capture asked to retry and resends them under the same request id`() {
        val ok = event("ok")
        val dropped = event("dropped")
        val retry = event("retry")
        val http = httpServers.mockHttp(response = resultsResponse(ok.uuid!! to "ok", dropped.uuid!! to "drop", retry.uuid!! to "retry"))
        http.enqueue(resultsResponse(retry.uuid!! to "ok"))
        val sut = queue(http, flushAt = 3)

        sut.add(ok)
        sut.add(dropped)
        sut.add(retry)
        executor.awaitExecution()

        assertEquals(1, http.requestCount)
        assertEquals(1, sut.dequeList.size)
        assertEquals(1, sut.currentRetryCountForTesting)
        assertEquals(
            listOf(retry.uuid.toString()),
            sut.dequeList.map { file -> file.reader().use { serializer.deserialize<PostHogEvent>(it).uuid.toString() } },
        )

        sut.flush()
        executor.awaitExecution()

        assertEquals(2, http.requestCount)
        assertTrue(sut.dequeList.isEmpty())
        assertEquals(0, sut.currentRetryCountForTesting)
        val first = http.takeRequest()
        val second = http.takeRequest()
        assertEquals(listOf(retry.uuid.toString()), batch(second).map { it["uuid"] })
        assertEquals("2", second.headers["PostHog-Attempt"])
        assertEquals(first.headers["PostHog-Request-Id"], second.headers["PostHog-Request-Id"])
    }

    @Test
    fun `queue resends a failed batch alone under the same request id`() {
        val failed = event("failed")
        val later = event("later")
        val http = httpServers.mockHttp(response = MockResponse().setResponseCode(503))
        http.enqueue(resultsResponse(failed.uuid!! to "ok"))
        http.enqueue(resultsResponse(later.uuid!! to "ok"))
        val sut = queue(http)

        sut.add(failed)
        executor.awaitExecution()
        sut.add(later)
        executor.awaitExecution()
        sut.flush()
        executor.awaitExecution()

        assertEquals(3, http.requestCount)
        val requests = List(3) { http.takeRequest() }
        assertEquals(
            listOf(listOf(failed.uuid.toString()), listOf(failed.uuid.toString()), listOf(later.uuid.toString())),
            requests.map { request -> batch(request).map { it["uuid"] } },
        )
        assertEquals(listOf("1", "2", "1"), requests.map { it.headers["PostHog-Attempt"] })
        assertEquals(requests[0].headers["PostHog-Request-Id"], requests[1].headers["PostHog-Request-Id"])
        assertNotEquals(requests[1].headers["PostHog-Request-Id"], requests[2].headers["PostHog-Request-Id"])
        assertTrue(sut.dequeList.isEmpty())
    }

    @Test
    fun `queue resends only the retried events while newer ones wait`() {
        val ok = event("ok")
        val retry = event("retry")
        val later = event("later")
        val http = httpServers.mockHttp(response = resultsResponse(ok.uuid!! to "ok", retry.uuid!! to "retry"))
        http.enqueue(resultsResponse(retry.uuid!! to "ok"))
        val sut = queue(http, flushAt = 2)

        sut.add(ok)
        sut.add(retry)
        executor.awaitExecution()
        sut.add(later)
        executor.awaitExecution()

        assertEquals(2, http.requestCount)
        val first = http.takeRequest()
        val second = http.takeRequest()
        assertEquals(listOf(retry.uuid.toString()), batch(second).map { it["uuid"] })
        assertEquals("2", second.headers["PostHog-Attempt"])
        assertEquals(first.headers["PostHog-Request-Id"], second.headers["PostHog-Request-Id"])
        assertEquals(1, sut.dequeList.size)
    }

    @Test
    fun `AI queue keeps only the events the AI endpoint asked to retry`() {
        val ok = event("\$ai_generation")
        val retry = event("\$ai_span")
        val http = httpServers.mockHttp(response = resultsResponse(ok.uuid!! to "ok", retry.uuid!! to "retry"))
        val sut = queue(http, flushAt = 2, ai = true)

        sut.add(ok)
        sut.add(retry)
        executor.awaitExecution()

        assertEquals("/i/v1/ai/events", http.takeRequest().path)
        assertEquals(1, sut.currentRetryCountForTesting)
        assertEquals(
            listOf(retry.uuid.toString()),
            sut.dequeList.map { file -> file.reader().use { serializer.deserialize<PostHogEvent>(it).uuid.toString() } },
        )
    }

    @Test
    fun `queue drops the batch on 400 and keeps it on 429`() {
        val http = httpServers.mockHttp(response = MockResponse().setResponseCode(400))
        http.enqueue(MockResponse().setResponseCode(429))
        val sut = queue(http)

        sut.add(event())
        executor.awaitExecution()
        assertTrue(sut.dequeList.isEmpty())

        sut.add(event())
        executor.awaitExecution()
        assertEquals(1, sut.dequeList.size)
    }

    @Test
    fun `queue drops a batch whose 2xx results can't be read without resending it`() {
        val http = httpServers.mockHttp(response = MockResponse().setBody("not json"))
        http.enqueue(resultsResponse())
        val sut = queue(http)

        sut.add(event())
        executor.awaitExecution()

        assertTrue(sut.dequeList.isEmpty())
        assertEquals(1, http.requestCount)
        assertFalse(logger.messages.any { it.contains("successfully") })
    }

    @Test
    fun `queued events round-trip their options through disk`() {
        val http = httpServers.mockHttp(response = MockResponse().setResponseCode(503))
        val sut = queue(http)

        sut.add(event(options = mapOf("process_person_profile" to false, "product_tour_id" to 7)))
        executor.awaitExecution()

        val stored = sut.dequeList.single().reader().use { serializer.deserialize<PostHogEvent>(it) }
        val options = mapOf<String, Any?>("process_person_profile" to false, "product_tour_id" to 7)
        assertEquals<Map<String, Any?>?>(options, stored.options)
        assertEquals(options, batch(http.takeRequest()).single().options())
    }

    @Test
    fun `a 3x event queued on disk is sent in the capture v1 shape`() {
        val uuid = UUID.randomUUID()
        val storagePrefix = tmpDir.newFolder().absolutePath
        File(storagePrefix, PROJECT_TOKEN).apply { mkdirs() }.resolve("$uuid.event").writeText(
            "{\"uuid\":\"$uuid\",\"event\":\"legacy\",\"distinct_id\":\"d\",\"timestamp\":\"2023-09-19T07:23:00.165Z\"," +
                "\"properties\":{\"a\":\"b\",\"\$process_person_profile\":false,\"\$lib\":\"posthog-android\"," +
                "\"\$lib_version\":\"3.20.0\",\"\$session_id\":\"session\"}}",
        )
        val http = httpServers.mockHttp(response = resultsResponse(uuid to "ok"))
        val sut = queue(http, storagePrefix = storagePrefix)

        sut.flush()
        executor.awaitExecution()

        val sent = batch(http.takeRequest()).single()
        assertEquals(uuid.toString(), sent["uuid"])
        assertEquals("session", sent["session_id"])
        assertEquals(mapOf<String, Any?>("process_person_profile" to false), sent.options())
        assertEquals(mapOf<String, Any?>("a" to "b"), sent.properties())
        assertTrue(sut.dequeList.isEmpty())
    }

    @Test
    fun `queued events without options still decode`() {
        val event = event()
        val json =
            "{\"uuid\":\"${event.uuid}\",\"event\":\"legacy\",\"distinct_id\":\"d\"," +
                "\"properties\":{\"a\":1},\"timestamp\":\"2023-09-19T07:23:00.165Z\"}"

        val decoded = serializer.deserialize<PostHogEvent>(json.reader())

        assertEquals("legacy", decoded.event)
        assertNull(decoded.options)
    }
}
