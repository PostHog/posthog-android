/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Adapted from https://github.com/square/okhttp/blob/f4ff4f4a8dce5f44596115f9564280e41d845f98/samples/guide/src/main/java/okhttp3/recipes/RequestBodyCompression.java
 */

package com.posthog.internal

import com.posthog.PostHogConfig
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.GzipSink
import okio.buffer
import java.io.IOException

// https://square.github.io/okhttp/features/interceptors/

/**
 * This interceptor compresses the HTTP request body. Many webservers can't handle this!
 * @property config The Config
 */
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
            val compressedRequest =
                try {
                    originalRequest.newBuilder()
                        .header("Content-Encoding", "gzip")
                        .method(originalRequest.method, forceContentLength(gzip(body)))
                        .build()
                } catch (e: Throwable) {
                    config.logger.log("Failed to gzip the request body: $e.")

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

    // https://github.com/square/okhttp/issues/350
    @Throws(IOException::class)
    private fun forceContentLength(body: RequestBody): RequestBody {
        val buffer = Buffer()
        body.writeTo(buffer)

        return object : RequestBody() {
            override fun contentType(): MediaType? {
                return body.contentType()
            }

            override fun contentLength(): Long {
                return buffer.size
            }

            @Throws(IOException::class)
            override fun writeTo(sink: BufferedSink) {
                sink.write(buffer.snapshot())
            }
        }
    }
}
