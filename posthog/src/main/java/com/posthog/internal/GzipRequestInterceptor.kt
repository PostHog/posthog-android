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

import com.posthog.PostHogCompression
import com.posthog.PostHogConfig
import com.posthog.PostHogInternal
import okhttp3.Interceptor
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

// https://square.github.io/okhttp/features/interceptors/

/**
 * This interceptor compresses the HTTP request body. Many webservers can't handle this!
 * @property config The Config
 */
@PostHogInternal
public class GzipRequestInterceptor(private val config: PostHogConfig) : Interceptor {
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val body = originalRequest.body

        return if (config.compression != PostHogCompression.GZIP ||
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
                        .method(originalRequest.method, gzip(body))
                        .build()
                } catch (e: Throwable) {
                    config.logger.log("Failed to gzip the request body: $e.")

                    originalRequest
                }
            chain.proceed(compressedRequest)
        }
    }

    // Okio's GzipSink can emit corrupt output with Okio versions before 3.11.0 (square/okio#1608).
    // Apps resolve their own Okio version, so compress with the JDK instead.
    // Buffering the result also sets Content-Length, see https://github.com/square/okhttp/issues/350
    @Throws(IOException::class)
    private fun gzip(body: RequestBody): RequestBody {
        val uncompressed = Buffer()
        body.writeTo(uncompressed)

        val compressed = ByteArrayOutputStream()
        GZIPOutputStream(compressed).use { uncompressed.writeTo(it) }

        return compressed.toByteArray().toRequestBody(body.contentType())
    }
}
