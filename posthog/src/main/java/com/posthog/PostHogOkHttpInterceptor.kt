package com.posthog

import com.posthog.internal.replay.RRPluginEvent
import com.posthog.internal.replay.capture
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Interceptor that captures network telemetry for session replay and, when
 * [PostHogConfig.tracingHeaders] is configured, injects PostHog tracing headers into matching
 * OkHttp requests.
 *
 * Install this interceptor on each [okhttp3.OkHttpClient] whose requests should be captured or
 * annotated with tracing headers.
 */
public class PostHogOkHttpInterceptor(
    private var captureNetworkTelemetry: Boolean = true,
    private val postHog: PostHogInterface? = null,
) : Interceptor {
    @JvmOverloads
    public constructor(captureNetworkTelemetry: Boolean = true) : this(captureNetworkTelemetry, null)

    private val currentPostHog: PostHogInterface
        get() = postHog ?: PostHog

    private val isSessionReplayActive: Boolean
        get() = postHog?.isSessionReplayActive() ?: PostHog.isSessionReplayActive()

    private val isNetworkCaptureEnabled: Boolean
        get() {
            val config = currentPostHog.getConfig<PostHogConfig>() ?: return true
            val remoteConfig = config.remoteConfigHolder
            // if remote config hasn't loaded yet, default to true (don't block locally enabled capture)
            return remoteConfig?.isCaptureNetworkTimingEnabled() ?: true
        }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = addPostHogTracingHeaders(chain.request())

        val response = chain.proceed(request)

        captureNetworkEvent(request, response)

        return response
    }

    private fun addPostHogTracingHeaders(request: Request): Request {
        val config = currentPostHog.getConfig<PostHogConfig>() ?: return request

        return addTracingHeadersToRequest(
            request = request,
            hostnames = config.tracingHeaders,
            distinctId = currentPostHog.distinctId(),
            sessionId = currentPostHog.getSessionId()?.toString(),
        )
    }

    private fun captureNetworkEvent(
        request: Request,
        response: Response,
    ) {
        // do not capture network events if locally or remotely disabled, or if session replay is disabled
        if (!captureNetworkTelemetry || !isNetworkCaptureEnabled || !isSessionReplayActive) {
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

        val events = listOf(RRPluginEvent("rrweb/network@1", payload, end))

        // its not guaranteed that the posthog instance is set
        events.capture(postHog)
    }
}

private const val POSTHOG_DISTINCT_ID_HEADER = "X-POSTHOG-DISTINCT-ID"
private const val POSTHOG_SESSION_ID_HEADER = "X-POSTHOG-SESSION-ID"

private fun addTracingHeadersToRequest(
    request: Request,
    hostnames: List<String>?,
    distinctId: String,
    sessionId: String?,
): Request {
    if (sessionId.isNullOrBlank() && distinctId.isBlank()) {
        return request
    }

    val normalizedHostnames = normalizeTracingHeaderHostnames(hostnames) ?: return request
    if (normalizedHostnames.isEmpty()) {
        return request
    }

    val requestHost = request.url.host.lowercase()
    if (!normalizedHostnames.contains(requestHost)) {
        return request
    }

    val requestBuilder = request.newBuilder()

    if (!sessionId.isNullOrBlank()) {
        requestBuilder.header(POSTHOG_SESSION_ID_HEADER, sessionId)
    }

    if (distinctId.isNotBlank()) {
        requestBuilder.header(POSTHOG_DISTINCT_ID_HEADER, distinctId)
    }

    return requestBuilder.build()
}

private fun normalizeTracingHeaderHostnames(hostnames: List<String>?): Set<String>? {
    return hostnames
        ?.asSequence()
        ?.map { it.trim().lowercase() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
}
