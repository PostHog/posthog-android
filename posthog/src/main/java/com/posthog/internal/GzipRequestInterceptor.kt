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
import com.posthog.PostHogInternal
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
import java.util.concurrent.atomic.AtomicReference

// https://square.github.io/okhttp/features/interceptors/

/**
 * This interceptor compresses the HTTP request body. Many webservers can't handle this!
 * @property config The Config
 */
@PostHogInternal
public class GzipRequestInterceptor(private val config: PostHogConfig) : Interceptor {
    private companion object {
        private const val HTTP_BAD_REQUEST = 400
        private const val MAX_ERROR_BODY_BYTES = 512L

        // What the server answers when it cannot read the compressed body, e.g. because a managed
        // work profile decompressed or re-encoded it but kept the Content-Encoding header.
        private val DECODE_ERROR_HINTS = listOf("gzip", "decompress", "unexpected end of file")
    }

    private enum class Compression {
        ON,

        // One thread claimed the probe. It retries that body uncompressed, and the SDK keeps
        // compressing if the retry does not help, instead of sending every rejected body twice.
        PROBED,

        // The server cannot read compressed bodies, e.g. because the network alters them in transit.
        OFF,
    }

    private val compression = AtomicReference(Compression.ON)

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val body = originalRequest.body

        return if (!config.compressRequestBody ||
            compression.get() == Compression.OFF ||
            body == null ||
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

                    return chain.proceed(originalRequest)
                }

            val response = chain.proceed(compressedRequest)

            // Claim the probe atomically. Every executor shares this interceptor, so two rejected
            // bodies must not probe twice, and a late claim must not put the state back to PROBED
            // after another thread turned compression off.
            if (compression.get() != Compression.ON ||
                !isCompressionRejected(response) ||
                !compression.compareAndSet(Compression.ON, Compression.PROBED)
            ) {
                return response
            }
            response.close()

            // Send the body again uncompressed, to find out whether compression is what the
            // server could not read.
            val uncompressedResponse = chain.proceed(originalRequest)
            if (uncompressedResponse.isSuccessful) {
                compression.set(Compression.OFF)
                config.logger.log("The server rejected a gzipped request body, compression is now off.")
            }
            uncompressedResponse
        }
    }

    private fun isCompressionRejected(response: Response): Boolean {
        if (response.code != HTTP_BAD_REQUEST) {
            return false
        }
        val body =
            try {
                response.peekBody(MAX_ERROR_BODY_BYTES).string().lowercase()
            } catch (e: Throwable) {
                config.logger.log("Failed to read the error response body: $e.")

                return false
            }
        return DECODE_ERROR_HINTS.any { it in body }
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
