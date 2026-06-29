package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogInternal
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Adds the caller-supplied [PostHogConfig.requestHeaders] (e.g. an `Authorization` header for a
 * reverse proxy) to every request the SDK sends.
 * @property config The Config
 */
@PostHogInternal
public class CustomHeadersInterceptor(private val config: PostHogConfig) : Interceptor {
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val requestHeaders = config.requestHeaders
        if (requestHeaders.isEmpty()) {
            return chain.proceed(chain.request())
        }

        val builder = chain.request().newBuilder()
        for ((key, value) in requestHeaders) {
            builder.header(key, value)
        }
        return chain.proceed(builder.build())
    }
}
