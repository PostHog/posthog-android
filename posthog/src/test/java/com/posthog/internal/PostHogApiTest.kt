package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.apiKey
import com.posthog.generateEvent
import com.posthog.responseApi
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

internal class PostHogApiTest {

    private fun getSut(
        host: String = "https://app.posthog.com",
    ): PostHogApi {
        val config = PostHogConfig(apiKey, host)
        val serializer = PostHogSerializer(config)
        return PostHogApi(config, serializer)
    }

    private fun mockHttp(
        response: MockResponse = MockResponse()
            .setBody(""),
    ): MockWebServer {
        val mock = MockWebServer()
        mock.start()
        mock.enqueue(response)
        return mock
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
        val http = mockHttp(MockResponse().setResponseCode(400).setBody("error"))
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        val event = generateEvent()
        val events = listOf(event)

        val exc = assertThrows(PostHogApiError::class.java) {
            sut.batch(events)
        }
        assertEquals(400, exc.statusCode)
        assertEquals("Client Error", exc.message)
        assertNotNull(exc.body)
    }

    @Test
    fun `decide returns successful response`() {
        val http = mockHttp(
            MockResponse()
                .setBody(responseApi),
        )
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        val response = sut.decide("distinctId", "anonId", emptyMap())

        val request = http.takeRequest()

        assertNotNull(response)
        assertEquals("posthog-java/${BuildConfig.VERSION_NAME}", request.headers["User-Agent"])
        assertEquals("POST", request.method)
        assertEquals("/decide/?v=3", request.path)
        assertEquals("gzip", request.headers["Content-Encoding"])
        assertEquals("gzip", request.headers["Accept-Encoding"])
        assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])
    }

    @Test
    fun `decide throws if not successful`() {
        val http = mockHttp(MockResponse().setResponseCode(400).setBody("error"))
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        val exc = assertThrows(PostHogApiError::class.java) {
            sut.decide("distinctId", "anonId", emptyMap())
        }
        assertEquals(400, exc.statusCode)
        assertEquals("Client Error", exc.message)
        assertNotNull(exc.body)
    }
}
