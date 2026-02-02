package com.posthog.internal

import com.posthog.API_KEY
import com.posthog.BuildConfig
import com.posthog.PostHogConfig
import com.posthog.generateEvent
import com.posthog.mockHttp
import com.posthog.unGzip
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertThrows
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogApiTest {
    private fun getSut(
        host: String,
        proxy: Proxy? = null,
    ): PostHogApi {
        val config = PostHogConfig(API_KEY, host)
        config.proxy = proxy
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

        val response = sut.flags("distinctId", anonymousId = "anonId", emptyMap())

        val request = http.takeRequest()

        assertNotNull(response)
        assertEquals("posthog-java/${BuildConfig.VERSION_NAME}", request.headers["User-Agent"])
        assertEquals("POST", request.method)
        assertEquals("/flags/?v=2&config=true", request.path)
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
                sut.flags("distinctId", anonymousId = "anonId", emptyMap())
            }
        assertEquals(400, exc.statusCode)
        assertEquals("Client Error", exc.message)
        assertNotNull(exc.body)
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

    @Test
    fun `registerPushSubscription returns successful response`() {
        val responseBody = """{"status": "ok", "subscription_id": "test-subscription-id"}"""
        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setResponseCode(200)
                        .setBody(responseBody),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.registerPushSubscription("test-distinct-id", "test-fcm-token", "test-firebase-project-id")

        val request = http.takeRequest()

        assertEquals("posthog-java/${BuildConfig.VERSION_NAME}", request.headers["User-Agent"])
        assertEquals("POST", request.method)
        assertEquals("/api/sdk/push_subscriptions/register", request.path)
        assertEquals("gzip", request.headers["Content-Encoding"])
        assertEquals("gzip", request.headers["Accept-Encoding"])
        assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])

        // Verify request body contains expected fields
        val requestBody = request.body.unGzip()
        assertTrue(requestBody.contains("\"api_key\":\"$API_KEY\""))
        assertTrue(requestBody.contains("\"distinct_id\":\"test-distinct-id\""))
        assertTrue(requestBody.contains("\"token\":\"test-fcm-token\""))
        assertTrue(requestBody.contains("\"platform\":\"android\""))
        assertTrue(requestBody.contains("\"fcm_project_id\":\"test-firebase-project-id\""))
        assertTrue(requestBody.contains("\"provider\":\"fcm\""))
    }

    @Test
    fun `registerPushSubscription throws if not successful`() {
        val http = mockHttp(response = MockResponse().setResponseCode(400).setBody("Bad Request"))
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        val exc =
            assertThrows(PostHogApiError::class.java) {
                sut.registerPushSubscription("test-distinct-id", "test-fcm-token", "test-firebase-project-id")
            }
        assertEquals(400, exc.statusCode)
        assertEquals("Client Error", exc.message)
        assertNotNull(exc.body)
    }

    @Test
    fun `registerPushSubscription throws on 401 unauthorized`() {
        val http = mockHttp(response = MockResponse().setResponseCode(401).setBody("""{"error": "Invalid API key"}"""))
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        val exc =
            assertThrows(PostHogApiError::class.java) {
                sut.registerPushSubscription("test-distinct-id", "test-fcm-token", "test-firebase-project-id")
            }
        assertEquals(401, exc.statusCode)
        assertEquals("Client Error", exc.message)
    }
}
