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
        val transferSize = (response.body?.contentLength() ?: 0) + (request.body?.contentLength() ?: 0)

        val requestMap = mutableMapOf<String, Any>()

        var cache = false
        response.cacheResponse?.let {
            cache = true
        }
        if (transferSize >= 0) {
            // the UI special case if the transferSize is 0 as coming from cache
            requestMap["transferSize"] = if (!cache) transferSize else 0
        }

        requestMap.putAll(
            mapOf(
                "name" to url,
                "method" to method,
                "responseStatus" to statusCode,
                "timestamp" to end,
                "duration" to (end - start),
                // TODO: remove it if we don't need it (server defaults to fetch)
                "initiatorType" to "fetch",
                "entryType" to "resource",
            ),
        )
        val requests = listOf(requestMap)
        val payload = mapOf<String, Any>("requests" to requests)

        listOf(RRPluginEvent("rrweb/network@1", payload, end)).capture()
    }
}
