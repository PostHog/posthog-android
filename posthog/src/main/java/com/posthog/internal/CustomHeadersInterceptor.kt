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

        val request = chain.request()
        val builder = request.newBuilder()
        for ((key, value) in requestHeaders) {
            // Keep headers the SDK already set on the request (e.g. localEvaluation's
            // Authorization personal API key); those take precedence over config values.
            if (request.header(key) == null) {
                builder.header(key, value)
            }
        }
        return chain.proceed(builder.build())
    }
}
