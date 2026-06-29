package com.posthog.internal

import com.posthog.API_KEY
import com.posthog.BuildConfig
import com.posthog.PostHogConfig
import com.posthog.generateEvent
import com.posthog.logs.PostHogLogRecord
import com.posthog.logs.PostHogLogSeverity
import com.posthog.mockHttp
import com.posthog.unGzip
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertThrows
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogApiTest {
    private class TestLogger : PostHogLogger {
        val messages = mutableListOf<String>()

        override fun log(message: String) {
            messages.add(message)
        }

        override fun isEnabled(): Boolean = true
    }

    private fun getSut(
        host: String,
        proxy: Proxy? = null,
        debug: Boolean = false,
        logger: PostHogLogger? = null,
        httpClient: OkHttpClient? = null,
        maxRetries: Int? = null,
        featureFlagRequestMaxRetries: Int? = null,
        requestHeaders: Map<String, String>? = null,
    ): PostHogApi {
        val config = PostHogConfig(API_KEY, host)
        config.proxy = proxy
        config.debug = debug
        if (requestHeaders != null) {
            config.requestHeaders = requestHeaders
        }
        if (logger != null) {
            config.logger = logger
        }
        if (httpClient != null) {
            config.httpClient = httpClient
        }
        if (maxRetries != null) {
            config.maxRetries = maxRetries
        }
        if (featureFlagRequestMaxRetries != null) {
            config.featureFlagRequestMaxRetries = featureFlagRequestMaxRetries
        }
        return PostHogApi(config)
    }

    @Test
    fun `batch returns successful response`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        val event = generateEvent()
        val events = listOf(event)

        sut.batch(events)

        val request = http.takeRequest()

        assertEquals("posthog-java/${BuildConfig.VERSION_NAME}", request.headers["User-Agent"])
        assertEquals("POST", request.method)
        assertEquals("/batch", request.path)
        assertEquals("gzip", request.headers["Content-Encoding"])
        assertEquals("gzip", request.headers["Accept-Encoding"])
        assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])
    }

    @Test
    fun `batch includes custom request headers`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(host = url.toString(), requestHeaders = mapOf("Authorization" to "Bearer test-jwt"))

        sut.batch(listOf(generateEvent()))

        val request = http.takeRequest()
        assertEquals("Bearer test-jwt", request.headers["Authorization"])
    }

    @Test
    fun `flags includes custom request headers`() {
        val file = File("src/test/resources/json/flags-v1/basic-flags-no-errors.json")
        val http = mockHttp(response = MockResponse().setBody(file.readText()))
        val url = http.url("/")

        val sut = getSut(host = url.toString(), requestHeaders = mapOf("Authorization" to "Bearer test-jwt"))

        sut.flags("distinctId", anonymousId = "anonId", groups = emptyMap())

        val request = http.takeRequest()
        assertEquals("Bearer test-jwt", request.headers["Authorization"])
    }

    @Test
    fun `does not send an Authorization header when no custom headers are set`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.batch(listOf(generateEvent()))

        val request = http.takeRequest()
        assertNull(request.headers["Authorization"])
    }

    @Test
    fun `batch throws if not successful`() {
        val http = mockHttp(response = MockResponse().setResponseCode(400).setBody("error"))
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        val event = generateEvent()
        val events = listOf(event)

        val exc =
            assertThrows(PostHogApiError::class.java) {
                sut.batch(events)
            }
        assertEquals(400, exc.statusCode)
        assertEquals("Client Error", exc.message)
        assertNotNull(exc.body)
    }

    @Test
    fun `flags returns successful response - v3`() {
        val file = File("src/test/resources/json/flags-v1/basic-flags-no-errors.json")
        val responseFlagsApi = file.readText()

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        val response = sut.flags("distinctId", anonymousId = "anonId", groups = emptyMap())

        val request = http.takeRequest()

        assertNotNull(response)
        assertEquals("posthog-java/${BuildConfig.VERSION_NAME}", request.headers["User-Agent"])
        assertEquals("POST", request.method)
        assertEquals("/flags/?v=2", request.path)
        assertEquals("gzip", request.headers["Content-Encoding"])
        assertEquals("gzip", request.headers["Accept-Encoding"])
        assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])
    }

    @Test
    fun `flags throws if not successful`() {
        val http = mockHttp(response = MockResponse().setResponseCode(400).setBody("error"))
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        val exc =
            assertThrows(PostHogApiError::class.java) {
                sut.flags("distinctId", anonymousId = "anonId", groups = emptyMap())
            }
        assertEquals(400, exc.statusCode)
        assertEquals("Client Error", exc.message)
        assertNotNull(exc.body)
    }

    @Test
    fun `flags retries transient IOException and returns successful response`() {
        val file = File("src/test/resources/json/flags-v1/basic-flags-no-errors.json")
        val responseFlagsApi = file.readText()
        val attempts = AtomicInteger(0)
        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    if (attempts.incrementAndGet() == 1) {
                        throw SocketException("Connection reset")
                    }
                    chain.proceed(chain.request())
                }
                .build()
        val http = mockHttp(response = MockResponse().setBody(responseFlagsApi))
        val url = http.url("/")

        try {
            val sut = getSut(host = url.toString(), httpClient = client, featureFlagRequestMaxRetries = 1)

            val response = sut.flags("distinctId", anonymousId = "anonId", groups = emptyMap())

            assertNotNull(response)
            assertEquals(2, attempts.get())
            assertEquals(1, http.requestCount)
        } finally {
            http.shutdown()
        }
    }

    @Test
    fun `flags does not retry when feature flag request max retries is zero`() {
        val attempts = AtomicInteger(0)
        val client =
            OkHttpClient.Builder()
                .addInterceptor {
                    attempts.incrementAndGet()
                    throw SocketException("Connection reset")
                }
                .build()
        val http = mockHttp(response = MockResponse().setBody("{}"))
        val url = http.url("/")

        try {
            val sut = getSut(host = url.toString(), httpClient = client, featureFlagRequestMaxRetries = 0)

            assertThrows(IOException::class.java) {
                sut.flags("distinctId", anonymousId = "anonId", groups = emptyMap())
            }

            assertEquals(1, attempts.get())
            assertEquals(0, http.requestCount)
        } finally {
            http.shutdown()
        }
    }

    @Test
    fun `flags rethrows retryable IOException after max retry attempts`() {
        val attempts = AtomicInteger(0)
        val client =
            OkHttpClient.Builder()
                .addInterceptor {
                    attempts.incrementAndGet()
                    throw SocketException("Connection reset")
                }
                .build()
        val http = mockHttp(response = MockResponse().setBody("{}"))
        val url = http.url("/")

        try {
            val sut = getSut(host = url.toString(), httpClient = client, featureFlagRequestMaxRetries = 1)

            assertThrows(IOException::class.java) {
                sut.flags("distinctId", anonymousId = "anonId", groups = emptyMap())
            }

            assertEquals(2, attempts.get())
            assertEquals(0, http.requestCount)
        } finally {
            http.shutdown()
        }
    }

    @Test
    fun `flags does not retry connection refused`() {
        val attempts = AtomicInteger(0)
        val client =
            OkHttpClient.Builder()
                .addInterceptor {
                    attempts.incrementAndGet()
                    throw SocketException("Connection refused")
                }
                .build()
        val http = mockHttp(response = MockResponse().setBody("{}"))
        val url = http.url("/")

        try {
            val sut = getSut(host = url.toString(), httpClient = client, featureFlagRequestMaxRetries = 1)

            assertThrows(IOException::class.java) {
                sut.flags("distinctId", anonymousId = "anonId", groups = emptyMap())
            }

            assertEquals(1, attempts.get())
            assertEquals(0, http.requestCount)
        } finally {
            http.shutdown()
        }
    }

    @Test
    fun `remote config returns successful response`() {
        val file = File("src/test/resources/json/basic-remote-config.json")
        val responseApi = file.readText()

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        val response = sut.remoteConfig()

        val request = http.takeRequest()

        assertNotNull(response)
        assertEquals("posthog-java/${BuildConfig.VERSION_NAME}", request.headers["User-Agent"])
        assertEquals("GET", request.method)
        assertEquals("/array/${API_KEY}/config", request.path)
        assertEquals("gzip", request.headers["Accept-Encoding"])
        assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])
    }

    @Test
    fun `remote config throws if not successful`() {
        val http = mockHttp(response = MockResponse().setResponseCode(400).setBody("error"))
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        val exc =
            assertThrows(PostHogApiError::class.java) {
                sut.remoteConfig()
            }
        assertEquals(400, exc.statusCode)
        assertEquals("Client Error", exc.message)
        assertNotNull(exc.body)
    }

    @Test
    fun `client uses configured proxy for requests`() {
        val file = File("src/test/resources/json/basic-remote-config.json")
        val responseApi = file.readText()

        val hostname = "localhost"
        val port = 6375
        val proxyAddress = InetSocketAddress(hostname, port)
        val proxy = Proxy(Proxy.Type.HTTP, proxyAddress)

        val server = MockWebServer()
        val inetAddress = InetAddress.getByName(hostname)
        server.start(inetAddress, port)
        server.enqueue(MockResponse().setBody(responseApi))

        val url = server.url("/")
        val sut = getSut(host = url.toString(), proxy = proxy)
        val response = sut.remoteConfig()
        val request = server.takeRequest()

        assertNotNull(response)
        assertEquals(port, request.requestUrl?.port)
        assertEquals(hostname, request.requestUrl?.host)
        server.shutdown()
    }

    // Local Evaluation ETag Tests

    private fun createLocalEvaluationJson(): String =
        """
        {
            "flags": [
                {
                    "id": 1,
                    "name": "test-flag",
                    "key": "test-flag",
                    "active": true,
                    "filters": {
                        "groups": [
                            {
                                "properties": [],
                                "rollout_percentage": 100
                            }
                        ]
                    },
                    "version": 1
                }
            ],
            "group_type_mapping": {},
            "cohorts": {}
        }
        """.trimIndent()

    @Test
    fun `localEvaluation sends If-None-Match header when ETag provided`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(createLocalEvaluationJson())
                        .setHeader("ETag", "\"new-etag\""),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        val response = sut.localEvaluation("test-personal-key", etag = "\"previous-etag\"")

        val request = http.takeRequest()

        assertEquals("\"previous-etag\"", request.headers["If-None-Match"])
        assertEquals("\"new-etag\"", response.etag)
        assertTrue(response.wasModified)
        assertNotNull(response.result)
    }

    @Test
    fun `localEvaluation handles 304 Not Modified`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setResponseCode(304)
                        .setHeader("ETag", "\"same-etag\""),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        val response = sut.localEvaluation("test-personal-key", etag = "\"same-etag\"")

        assertFalse(response.wasModified)
        assertEquals("\"same-etag\"", response.etag)
        assertNull(response.result)
    }

    @Test
    fun `localEvaluation preserves request ETag on 304 when server omits ETag header`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setResponseCode(304),
                // No ETag header in response
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        val response = sut.localEvaluation("test-personal-key", etag = "\"original-etag\"")

        assertFalse(response.wasModified)
        // Should preserve the original ETag from the request
        assertEquals("\"original-etag\"", response.etag)
        assertNull(response.result)
    }

    @Test
    fun `localEvaluation works without ETag parameter`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(createLocalEvaluationJson())
                        .setHeader("ETag", "\"first-etag\""),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        val response = sut.localEvaluation("test-personal-key")

        val request = http.takeRequest()

        assertNull(request.headers["If-None-Match"])
        assertEquals("\"first-etag\"", response.etag)
        assertTrue(response.wasModified)
        assertNotNull(response.result)
    }

    @Test
    fun `localEvaluation throws on error response`() {
        val http = mockHttp(response = MockResponse().setResponseCode(401).setBody("Unauthorized"))
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        val exc =
            assertThrows(PostHogApiError::class.java) {
                sut.localEvaluation("test-personal-key")
            }
        assertEquals(401, exc.statusCode)
    }

    // Debug Header Logging Tests

    @Test
    fun `batch logs request headers in debug mode`() {
        val http = mockHttp()
        val url = http.url("/")
        val logger = TestLogger()

        val sut = getSut(host = url.toString(), debug = true, logger = logger)

        val event = generateEvent()
        sut.batch(listOf(event))

        assertTrue(
            logger.messages.any { it.contains("Request headers for") && it.contains("/batch") },
            "Should log request headers for /batch endpoint",
        )
        assertTrue(
            logger.messages.any { it.contains("User-Agent:") },
            "Should include User-Agent header in log",
        )
    }

    @Test
    fun `batch does not log headers when debug is disabled`() {
        val http = mockHttp()
        val url = http.url("/")
        val logger = TestLogger()

        val sut = getSut(host = url.toString(), debug = false, logger = logger)

        val event = generateEvent()
        sut.batch(listOf(event))

        assertFalse(
            logger.messages.any { it.contains("Request headers for") },
            "Should not log request headers when debug is disabled",
        )
    }

    @Test
    fun `flags logs request headers in debug mode`() {
        val file = File("src/test/resources/json/flags-v1/basic-flags-no-errors.json")
        val responseFlagsApi = file.readText()

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseFlagsApi),
            )
        val url = http.url("/")
        val logger = TestLogger()

        val sut = getSut(host = url.toString(), debug = true, logger = logger)

        sut.flags("distinctId", anonymousId = "anonId", groups = emptyMap())

        assertTrue(
            logger.messages.any { it.contains("Request headers for") && it.contains("/flags") },
            "Should log request headers for /flags endpoint",
        )
    }

    @Test
    fun `localEvaluation logs request headers including Authorization in debug mode`() {
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(createLocalEvaluationJson())
                        .setHeader("ETag", "\"test-etag\""),
            )
        val url = http.url("/")
        val logger = TestLogger()

        val sut = getSut(host = url.toString(), debug = true, logger = logger)

        sut.localEvaluation("test-personal-key")

        assertTrue(
            logger.messages.any { it.contains("Request headers for") && it.contains("/local_evaluation") },
            "Should log request headers for /local_evaluation endpoint",
        )
        assertTrue(
            logger.messages.any { it.contains("Authorization:") },
            "Should include Authorization header in log",
        )
    }

    @Test
    fun `remoteConfig logs request headers in debug mode`() {
        val file = File("src/test/resources/json/basic-remote-config.json")
        val responseApi = file.readText()

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseApi),
            )
        val url = http.url("/")
        val logger = TestLogger()

        val sut = getSut(host = url.toString(), debug = true, logger = logger)

        sut.remoteConfig()

        assertTrue(
            logger.messages.any { it.contains("Request headers for") && it.contains("/array/") },
            "Should log request headers for /array/ (remoteConfig) endpoint",
        )
    }

    @Test
    fun `sendLogs posts OTLP body to slash i v1 logs with token in query`() {
        val http = mockHttp()
        val url = http.url("/")
        val sut = getSut(host = url.toString())

        val record =
            PostHogLogRecord(
                body = "hello",
                level = PostHogLogSeverity.INFO,
                timeUnixNano = "1700000000000000000",
                observedTimeUnixNano = "1700000000000000000",
            )
        sut.sendLogs(listOf(record), emptyMap())

        val request = http.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/i/v1/logs?token=$API_KEY", request.path)
        assertEquals("gzip", request.headers["Content-Encoding"])
        assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])

        val unzipped = request.body.unGzip()
        // Smoke-test the OTLP wire shape — exhaustive shape coverage is in PostHogLogsOTLPTest.
        assertTrue(unzipped.contains("\"resourceLogs\""))
        assertTrue(unzipped.contains("\"scopeLogs\""))
        assertTrue(unzipped.contains("\"logRecords\""))
        assertTrue(unzipped.contains("\"telemetry.sdk.name\""))
        assertTrue(unzipped.contains("\"posthog-java\"") || unzipped.contains("\"posthog-android\""))
        assertTrue(unzipped.contains("\"stringValue\":\"hello\""))
    }

    @Test
    fun `sendLogs throws on non-success`() {
        val http = mockHttp(response = MockResponse().setResponseCode(500).setBody("oops"))
        val url = http.url("/")
        val sut = getSut(host = url.toString())

        val record = PostHogLogRecord(body = "x")
        val exc =
            assertThrows(PostHogApiError::class.java) {
                sut.sendLogs(listOf(record), emptyMap())
            }
        assertEquals(500, exc.statusCode)
    }

    @Test
    fun `sendLogs throws PostHogApiError on 408`() {
        // The whole reason isLogsRetriableStatusCode exists separately from the
        // events predicate is to cover 408. Lock in that the wire path surfaces
        // a 408 as a retriable PostHogApiError so the queue can keep records.
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setResponseCode(408)
                        .setHeader("Retry-After", "3")
                        .setBody("timeout"),
            )
        val sut = getSut(host = http.url("/").toString())

        val record = PostHogLogRecord(body = "x")
        val exc =
            assertThrows(PostHogApiError::class.java) {
                sut.sendLogs(listOf(record), emptyMap())
            }
        assertEquals(408, exc.statusCode)
    }
}

@RunWith(Parameterized::class)
internal class PostHogApiFlagsHttpErrorTest(
    private val statusCode: Int,
) {
    @Test
    fun `flags does not retry HTTP error responses`() {
        val http = mockHttp(response = MockResponse().setResponseCode(statusCode).setBody("error"))
        val url = http.url("/")

        try {
            val sut = PostHogApi(PostHogConfig(API_KEY, url.toString()))

            val exc =
                assertThrows(PostHogApiError::class.java) {
                    sut.flags("distinctId", anonymousId = "anonId", groups = emptyMap())
                }

            assertEquals(statusCode, exc.statusCode)
            assertEquals(1, http.requestCount)
        } finally {
            http.shutdown()
        }
    }

    private companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "statusCode={0}")
        fun statusCodes(): Collection<Array<Int>> =
            listOf(
                arrayOf(408),
                arrayOf(429),
                arrayOf(500),
                arrayOf(502),
                arrayOf(503),
            )
    }
}
