package com.posthog

import com.posthog.internal.RRPluginEvent
import com.posthog.internal.capture
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

public class PostHogOkHttpInterceptor(private val captureNetwork: Boolean = true) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        try {
            val response = chain.proceed(originalRequest)

            captureNetworkEvent(originalRequest, response)

            return response
        } catch (e: Throwable) {
            throw e
        }
    }

    private fun captureNetworkEvent(request: Request, response: Response) {
        if (!captureNetwork) {
            return
        }
        val url = request.url.toString()
        val method = request.method
        val statusCode = response.code
        val start = response.sentRequestAtMillis
        val end = response.receivedResponseAtMillis
        var transferSize = (response.body?.contentLength() ?: 0) + (request.body?.contentLength() ?: 0)

        if (transferSize < 0) {
            transferSize = 0
        }

        val requestMap = mapOf<String, Any>(
            "name" to url,
            "method" to method,
            "responseStatus" to statusCode,
            "timestamp" to end,
            "duration" to (end - start),
            "transferSize" to transferSize,
        )
        val requests = listOf(requestMap)
        val payload = mapOf<String, Any>("requests" to requests)

        listOf(RRPluginEvent("rrweb/network@1", payload, end)).capture()
    }
}
