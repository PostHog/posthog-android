package com.posthog

import com.posthog.internal.replay.RRPluginEvent
import com.posthog.internal.replay.capture
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

public class PostHogOkHttpInterceptor
    @JvmOverloads
    constructor(private val captureNetworkTelemetry: Boolean = true) : Interceptor {
        private val isSessionReplayEnabled: Boolean
            get() = PostHog.getConfig<PostHogConfig>()?.sessionReplay == true && PostHog.isSessionActive()

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

        private fun captureNetworkEvent(
            request: Request,
            response: Response,
        ) {
            // do not capture network logs if session replay is disabled
            if (!captureNetworkTelemetry || !isSessionReplayEnabled) {
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
                    "timestamp" to start,
                    "duration" to (end - start),
                    "initiatorType" to "fetch",
                    "entryType" to "resource",
                ),
            )
            val requests = listOf(requestMap)
            val payload = mapOf<String, Any>("requests" to requests)

            // TODO: get the android config and pass the sesson replay mode
            // likely needs a processor on the android side since this class
            // does not know about the PostHogAndroidConfig
            listOf(RRPluginEvent("rrweb/network@1", payload, end)).capture()
        }
    }
