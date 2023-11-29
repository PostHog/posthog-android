package com.posthog

import com.posthog.internal.RRPluginEvent
import com.posthog.internal.capture
import okhttp3.Interceptor
import okhttp3.Response

public class PostHogOkHttpInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        try {
            val response = chain.proceed(originalRequest)

            val url = originalRequest.url.toString()
            val method = originalRequest.method
            val statusCode = response.code
            val start = response.sentRequestAtMillis
            val end = response.receivedResponseAtMillis
            var transferSize = (response.body?.contentLength() ?: 0) + (originalRequest.body?.contentLength() ?: 0)

            if (transferSize < 0) {
                transferSize = 0
            }

            val request = mapOf<String, Any>(
                "name" to url,
                "method" to method,
                "status" to statusCode,
                "timestamp" to end,
                "duration" to (end - start),
                "transferSize" to transferSize,
            )
            val requests = listOf(request)
            val payload = mapOf<String, Any>("requests" to requests)

            listOf(RRPluginEvent("rrweb/network@1", payload, end)).capture()

            return response
        } catch (e: Throwable) {
            throw e
        }
    }
}
