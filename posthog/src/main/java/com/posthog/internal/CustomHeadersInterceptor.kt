package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogInternal
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Adds the caller-supplied [PostHogConfig.requestHeaders] (e.g. an `Authorization` header for a
 * reverse proxy) to every request the SDK sends.
 * @property config The Config
 */
@PostHogInternal
public class CustomHeadersInterceptor(private val config: PostHogConfig) : Interceptor {
    private val configuredHost: String? = config.host.toHttpUrlOrNull()?.host

    // Snapshot at construction so config is treated as immutable after setup.
    private val requestHeaders: Map<String, String> = config.requestHeaders

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // Only attach to the configured host so headers aren't sent to rewritten hosts (e.g. the CDN).
        if (requestHeaders.isEmpty() || request.url.host != configuredHost) {
            return chain.proceed(request)
        }

        val builder = request.newBuilder()
        for ((key, value) in requestHeaders) {
            // SDK-set request headers (e.g. localEvaluation's Authorization) take precedence.
            request.header(key) ?: builder.addCustomHeader(key, value)
        }
        return chain.proceed(builder.build())
    }

    private fun Request.Builder.addCustomHeader(
        key: String,
        value: String,
    ) {
        try {
            header(key, value)
        } catch (e: IllegalArgumentException) {
            // Drop headers okhttp rejects rather than failing the request.
            config.logger.log("Dropping invalid request header '$key': ${e.message}")
        }
    }
}
