package com.posthog.internal

import com.posthog.API_KEY
import com.posthog.BuildConfig
import com.posthog.PostHogConfig
import com.posthog.generateEvent
import com.posthog.mockHttp
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertThrows
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
    fun `decide returns successful response - v3`() {
        val file = File("src/test/resources/json/decide-v3/basic-decide-no-errors.json")
        val responseDecideApi = file.readText()

        val http =
            mockHttp(
                response =
                    MockResponse()
                        .setBody(responseDecideApi),
            )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        val response = sut.decide("distinctId", anonymousId = "anonId", emptyMap())

        val request = http.takeRequest()

        assertNotNull(response)
        assertEquals("posthog-java/${BuildConfig.VERSION_NAME}", request.headers["User-Agent"])
        assertEquals("POST", request.method)
        assertEquals("/decide/?v=4", request.path)
        assertEquals("gzip", request.headers["Content-Encoding"])
        assertEquals("gzip", request.headers["Accept-Encoding"])
        assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])
    }

    @Test
    fun `decide throws if not successful`() {
        val http = mockHttp(response = MockResponse().setResponseCode(400).setBody("error"))
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        val exc =
            assertThrows(PostHogApiError::class.java) {
                sut.decide("distinctId", anonymousId = "anonId", emptyMap())
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
        val request = server.takeRequest(1, TimeUnit.SECONDS)

        assertNotNull(response)
        assertEquals(port, request?.requestUrl?.port)
        assertEquals(hostname, request?.requestUrl?.host)
        server.shutdown()
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
}
