package com.posthog.internal

import com.posthog.PostHogConfig
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.GzipSink
import okio.buffer
import java.io.IOException

// https://square.github.io/okhttp/features/interceptors/

/** This interceptor compresses the HTTP request body. Many webservers can't handle this!  */
internal class GzipRequestInterceptor(private val config: PostHogConfig) : Interceptor {
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val body = originalRequest.body

        return if (body == null ||
            originalRequest.header("Content-Encoding") != null ||
            body is MultipartBody
        ) {
            chain.proceed(originalRequest)
        } else {
            val compressedRequest = try {
                originalRequest.newBuilder()
                    .header("Content-Encoding", "gzip")
                    .method(originalRequest.method, gzip(body))
                    .build()
            } catch (e: Throwable) {
                config.logger?.log("Failed to gzip the request body.")

                originalRequest
            }
            chain.proceed(compressedRequest)
        }
    }

    private fun gzip(body: RequestBody): RequestBody {
        return object : RequestBody() {
            override fun contentType(): MediaType? {
                return body.contentType()
            }

            override fun contentLength(): Long {
                return -1 // We don't know the compressed length in advance!
            }

            @Throws(IOException::class)
            override fun writeTo(sink: BufferedSink) {
                val gzipSink = GzipSink(sink).buffer()
                body.writeTo(gzipSink)
                gzipSink.close()
            }
        }
    }
}
