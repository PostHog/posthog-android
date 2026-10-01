package com.posthog.internal

import com.posthog.API_KEY
import com.posthog.PostHogConfig
import com.posthog.TestHttpServers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Rule
import java.util.Random
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals

internal class GzipRequestInterceptorTest {
    @get:Rule
    val httpServers = TestHttpServers()

    private fun post(payload: String): okhttp3.mockwebserver.RecordedRequest {
        val http = httpServers.mockHttp()
        val client =
            OkHttpClient.Builder()
                .addInterceptor(GzipRequestInterceptor(PostHogConfig(API_KEY)))
                .build()
        val request =
            Request.Builder()
                .url(http.url("/batch"))
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
        client.newCall(request).execute().close()
        return http.takeRequest()
    }

    private fun largePayload(): String {
        val random = Random(42)
        return buildString {
            append("[")
            repeat(20_000) { index ->
                if (index > 0) append(",")
                append("{\"event\":\"event_$index\",\"properties\":{\"value\":\"")
                repeat(64) { append('a' + random.nextInt(26)) }
                append("\"}}")
            }
            append("]")
        }
    }

    @Test
    fun `compressed body of a large payload decompresses to the original payload`() {
        val payload = largePayload()

        val request = post(payload)

        assertEquals("gzip", request.getHeader("Content-Encoding"))
        val compressed = request.body.readByteArray()
        assertEquals(compressed.size.toLong(), request.getHeader("Content-Length")?.toLong())
        val decompressed = GZIPInputStream(compressed.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
        assertEquals(payload, decompressed)
    }

    @Test
    fun `compressed body keeps the original content type`() {
        val request = post("{}")

        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
    }
}
