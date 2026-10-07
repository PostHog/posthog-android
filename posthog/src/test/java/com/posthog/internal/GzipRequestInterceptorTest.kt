package com.posthog.internal

import com.posthog.API_KEY
import com.posthog.PostHogConfig
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Random
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

internal class GzipRequestInterceptorTest {
    @Test
    fun `compressed request round trips multiple writes and flushes`() {
        val random = Random(0)
        for (size in listOf(121, 24_163)) {
            val payload = ByteArray(size).also { random.nextBytes(it) }
            val contentType = "application/octet-stream".toMediaType()
            val body =
                object : RequestBody() {
                    override fun contentType() = contentType

                    override fun writeTo(sink: BufferedSink) {
                        val split = payload.size / 2
                        sink.write(payload, 0, split)
                        sink.flush()
                        sink.write(payload, split, payload.size - split)
                        sink.flush()
                    }
                }
            val request = Request.Builder().url("https://example.com/batch").post(body).build()
            val chain = mock<Interceptor.Chain>()
            val response = mock<Response>()
            val compressedRequest = argumentCaptor<Request>()
            whenever(chain.request()).thenReturn(request)
            whenever(chain.proceed(compressedRequest.capture())).thenReturn(response)

            GzipRequestInterceptor(PostHogConfig(API_KEY)).intercept(chain)

            verify(chain).proceed(compressedRequest.lastValue)
            val compressedBody = compressedRequest.lastValue.body!!
            val buffer = Buffer()
            compressedBody.writeTo(buffer)
            val bytes = buffer.readByteArray()
            assertEquals("gzip", compressedRequest.lastValue.header("Content-Encoding"))
            assertEquals(contentType, compressedBody.contentType())
            assertEquals(bytes.size.toLong(), compressedBody.contentLength())
            GZIPInputStream(bytes.inputStream()).use { input ->
                assertContentEquals(payload, input.readBytes())
            }
        }
    }
}
