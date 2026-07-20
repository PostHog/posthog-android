package com.posthog.internal

import com.google.gson.JsonIOException
import com.google.gson.JsonSyntaxException
import com.posthog.PostHogConfig
import com.posthog.PostHogConfig.Companion.DEFAULT_EU_ASSETS_HOST
import com.posthog.PostHogConfig.Companion.DEFAULT_EU_HOST
import com.posthog.PostHogConfig.Companion.DEFAULT_US_ASSETS_HOST
import com.posthog.PostHogConfig.Companion.DEFAULT_US_HOST
import com.posthog.PostHogEvent
import com.posthog.PostHogInternal
import com.posthog.internal.logs.PostHogLogsOTLP
import com.posthog.logs.PostHogLogRecord
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSink
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * The class that calls the PostHog API
 * @property config the Config
 */
@PostHogInternal
public class PostHogApi(
    private val config: PostHogConfig,
) {
    private companion object {
        private const val APP_JSON_UTF_8 = "application/json; charset=utf-8"
        private const val FLAGS_INITIAL_RETRY_DELAY_MS = 300L
        private const val FLAGS_MAX_RETRY_DELAY_MS = 30_000L
    }

    private val mediaType by lazy {
        try {
            // can throw IllegalArgumentException
            APP_JSON_UTF_8.toMediaType()
        } catch (ignored: Throwable) {
            null
        }
    }

    private val client: OkHttpClient =
        config.httpClient ?: OkHttpClient.Builder()
            .proxy(config.proxy)
            .addInterceptor(GzipRequestInterceptor(config))
            // Network interceptor so the host check re-runs on each redirect hop.
            .addNetworkInterceptor(CustomHeadersInterceptor(config))
            .build()

    private val flagsClient: OkHttpClient by lazy {
        client.newBuilder()
            .retryOnConnectionFailure(false)
            .build()
    }

    private val theHost: String
        get() {
            return if (config.host.endsWith("/")) config.host.substring(0, config.host.length - 1) else config.host
        }

    @Throws(PostHogApiError::class, IOException::class)
    public fun batch(events: List<PostHogEvent>) {
        val batch = PostHogBatchEvent(config.apiKey, events)

        val url = "$theHost/batch"
        val request =
            makeRequest(url) {
                batch.sentAt = config.dateProvider.currentDate()

                logRequest(batch, url)

                config.serializer.serialize(batch, it.bufferedWriter())
            }

        executeNoBody(request)
    }

    @Throws(PostHogApiError::class, IOException::class)
    public fun snapshot(events: List<PostHogEvent>) {
        events.forEach {
            it.apiKey = config.apiKey
        }

        val url = "$theHost${config.snapshotEndpoint}"
        logRequest(events, url)

        // sent_at isn't supported by the snapshot endpoint
        val request =
            makeRequest(url) {
                config.serializer.serialize(events, it.bufferedWriter())
            }

        executeNoBody(request)
    }

    /**
     * Builds an OTLP/JSON payload for the supplied [records] and posts it to
     * `/i/v1/logs?token=<apiKey>`. Throws [PostHogApiError] on a non-success
     * status and [IOException] on a transport failure.
     *
     * `resourceAttributes` (e.g. `service.name`, `service.version`) is merged
     * with SDK-managed `telemetry.sdk.*` keys before serialization.
     */
    @Throws(PostHogApiError::class, IOException::class)
    internal fun sendLogs(
        records: List<PostHogLogRecord>,
        resourceAttributes: Map<String, Any>,
    ) {
        val payload =
            PostHogLogsOTLP.buildPayload(
                records = records,
                resourceAttributes = resourceAttributes,
                sdkName = config.sdkName,
                sdkVersion = config.sdkVersion,
            )

        val url = "$theHost/i/v1/logs?token=${config.apiKey}"
        val request =
            makeRequest(url) {
                logRequest(payload, url)
                config.serializer.serialize(payload, it.bufferedWriter())
            }

        executeNoBody(request)
    }

    @Throws(PostHogApiError::class, IOException::class)
    public fun pushSubscription(
        distinctId: String,
        deviceToken: String,
        platform: String,
        appId: String,
    ) {
        val pushSubscription =
            PostHogPushSubscriptionRequest(
                projectToken = config.apiKey,
                distinctId = distinctId,
                deviceToken = deviceToken,
                platform = platform,
                appId = appId,
            )

        val url = "$theHost/api/push_subscriptions/"
        val request =
            makeRequest(url) {
                logRequest(pushSubscription, url)

                config.serializer.serialize(pushSubscription, it.bufferedWriter())
            }

        executeNoBody(request)
    }

    @Throws(PostHogApiError::class, IOException::class)
    private fun executeNoBody(request: Request) {
        logRequestHeaders(request)

        client.newCall(request).execute().use {
            val response = logResponse(it)

            if (!response.isSuccessful) {
                throw PostHogApiError(response.code, response.message, response.body, parseRetryAfter(response))
            }
        }
    }

    private fun parseRetryAfter(response: Response): Int? {
        return try {
            response.header("Retry-After")?.toIntOrNull()
        } catch (e: Throwable) {
            null
        }
    }

    private fun makeRequest(
        url: String,
        serializer: (outputStream: OutputStream) -> Unit,
    ): Request {
        val requestBody =
            object : RequestBody() {
                override fun contentType() = mediaType

                override fun writeTo(sink: BufferedSink) {
                    sink.outputStream().use {
                        serializer(it)
                    }
                }
            }

        return Request.Builder()
            .url(url)
            .header("User-Agent", config.userAgent)
            .post(requestBody)
            .build()
    }

    @Throws(PostHogApiError::class, IOException::class)
    public fun flags(
        distinctId: String,
        anonymousId: String? = null,
        deviceId: String? = null,
        groups: Map<String, String>? = null,
        personProperties: Map<String, Any?>? = null,
        groupProperties: Map<String, Map<String, Any?>>? = null,
        flagKeys: List<String>? = null,
        disableGeoip: Boolean = false,
    ): PostHogFlagsResponse? {
        val flagsRequest =
            PostHogFlagsRequest(
                config.apiKey,
                distinctId,
                anonymousId = anonymousId,
                deviceId = deviceId,
                groups,
                personProperties,
                groupProperties,
                config.evaluationContexts,
                flagKeys = flagKeys,
                disableGeoip = disableGeoip,
            )

        val url = "$theHost/flags/?v=2"
        logRequest(flagsRequest, url)

        val request =
            makeRequest(url) {
                config.serializer.serialize(flagsRequest, it.bufferedWriter())
            }

        return executeFlagsWithRetry(request)
    }

    @Throws(PostHogApiError::class, IOException::class)
    private fun executeFlagsWithRetry(request: Request): PostHogFlagsResponse? {
        val maxRetries = config.featureFlagRequestMaxRetries.coerceAtLeast(0)
        var retryAttempt = 0

        while (true) {
            try {
                return executeFlagsRequest(request)
            } catch (e: Exception) {
                if (retryAttempt >= maxRetries || !isRetryableFlagsError(e)) {
                    throw e
                }
            }

            retryAttempt++
            sleepBeforeFlagsRetry(retryAttempt)
        }
    }

    private fun isRetryableFlagsError(error: Exception): Boolean {
        return when (error) {
            is PostHogApiError -> error.statusCode == 502 || error.statusCode == 504
            is IOException ->
                error is SocketTimeoutException ||
                    error is EOFException ||
                    (error is SocketException && error.message?.contains("reset", ignoreCase = true) == true)
            else -> false
        }
    }

    @Throws(PostHogApiError::class, IOException::class)
    private fun executeFlagsRequest(request: Request): PostHogFlagsResponse? {
        logRequestHeaders(request)

        flagsClient.newCall(request).execute().use {
            val response = logResponse(it)

            if (!response.isSuccessful) throw PostHogApiError(response.code, response.message, response.body)

            response.body?.let { body ->
                return deserializeFlagsResponse(body)
            }
            return null
        }
    }

    @Throws(IOException::class)
    private fun sleepBeforeFlagsRetry(retryAttempt: Int) {
        try {
            Thread.sleep(flagsRetryDelayMillis(retryAttempt))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting to retry feature flags request.", e)
        }
    }

    private fun flagsRetryDelayMillis(retryAttempt: Int): Long {
        var delay = FLAGS_INITIAL_RETRY_DELAY_MS
        repeat((retryAttempt - 1).coerceAtLeast(0)) {
            if (delay >= FLAGS_MAX_RETRY_DELAY_MS / 2) {
                return FLAGS_MAX_RETRY_DELAY_MS
            }
            delay *= 2
        }
        return delay.coerceAtMost(FLAGS_MAX_RETRY_DELAY_MS)
    }

    @Throws(PostHogApiError::class, IOException::class)
    public fun remoteConfig(): PostHogRemoteConfigResponse? {
        var host = theHost
        host =
            when (host) {
                DEFAULT_US_HOST -> {
                    DEFAULT_US_ASSETS_HOST
                }
                DEFAULT_EU_HOST -> {
                    DEFAULT_EU_ASSETS_HOST
                }
                else -> {
                    host
                }
            }

        val request =
            Request.Builder()
                .url("$host/array/${config.apiKey}/config")
                .header("User-Agent", config.userAgent)
                .header("Content-Type", APP_JSON_UTF_8)
                .get()
                .build()

        logRequestHeaders(request)

        client.newCall(request).execute().use {
            val response = logResponse(it)

            if (!response.isSuccessful) {
                throw PostHogApiError(
                    response.code,
                    response.message,
                    response.body,
                )
            }

            response.body?.let { body ->
                return config.serializer.deserialize(body.charStream().buffered())
            }
            return null
        }
    }

    /**
     * Fetches feature flag definitions for local evaluation.
     *
     * @param personalApiKey The personal API key for authentication.
     * @param etag Optional ETag from a previous request for conditional fetching.
     * @return A [LocalEvaluationApiResponse] containing the feature flags, ETag, and modification status.
     */
    @Throws(PostHogApiError::class, IOException::class)
    public fun localEvaluation(
        personalApiKey: String,
        etag: String? = null,
    ): LocalEvaluationApiResponse {
        val url = "$theHost/api/feature_flag/local_evaluation/?token=${config.apiKey}&send_cohorts"

        val requestBuilder =
            Request.Builder()
                .url(url)
                .header("User-Agent", config.userAgent)
                .header("Content-Type", APP_JSON_UTF_8)
                .header("Authorization", "Bearer $personalApiKey")

        // Add If-None-Match header for conditional request if we have an ETag
        if (!etag.isNullOrEmpty()) {
            requestBuilder.header("If-None-Match", etag)
        }

        val request = requestBuilder.get().build()

        logRequestHeaders(request)

        client.newCall(request).execute().use {
            val response = logResponse(it)

            // Get ETag from response (may be present even on 304)
            val responseEtag = response.header("ETag")

            // Handle 304 Not Modified - flags haven't changed
            if (response.code == 304) {
                config.logger.log("Feature flags not modified (304), using cached data")
                // Preserve the original ETag if the server didn't return one
                return LocalEvaluationApiResponse.notModified(responseEtag ?: etag)
            }

            if (!response.isSuccessful) {
                throw PostHogApiError(
                    response.code,
                    response.message,
                    response.body,
                )
            }

            response.body?.let { body ->
                val result: LocalEvaluationResponse? = config.serializer.deserialize(body.charStream().buffered())
                return LocalEvaluationApiResponse.success(result, responseEtag)
            }
            // Empty body on success is anomalous - clear ETag to force fresh fetch next time
            return LocalEvaluationApiResponse.success(null, null)
        }
    }

    private fun deserializeFlagsResponse(body: ResponseBody): PostHogFlagsResponse? =
        try {
            config.serializer.deserialize(body.charStream().buffered())
        } catch (e: Exception) {
            val reason =
                when (e) {
                    is JsonIOException,
                    is JsonSyntaxException,
                    -> "response was not valid JSON"
                    else -> "response could not be parsed"
                }
            config.logger.log("Loading feature flags failed: $reason: $e")
            null
        }

    private fun logResponse(response: Response): Response {
        if (config.debug) {
            try {
                val responseBody = response.body ?: return response
                val mediaType = responseBody.contentType()
                val content =
                    try {
                        responseBody.string()
                    } catch (e: Throwable) {
                        return response // can't read body, return original
                    }
                config.logger.log("Response ${response.request.url}: $content")

                // Rebuild the body so the response can still be used
                val newBody = content.toByteArray().toResponseBody(mediaType)
                return response.newBuilder().body(newBody).build()
            } catch (e: Throwable) {
                // ignore
            }
        }
        return response
    }

    private fun logRequest(
        body: Any,
        url: String,
    ) {
        if (config.debug) {
            try {
                config.serializer.serializeObject(body)?.let {
                    config.logger.log("Request $url}: $it")
                }
            } catch (e: Throwable) {
                // ignore
            }
        }
    }

    private fun logRequestHeaders(request: Request) {
        if (config.debug) {
            try {
                val headers = request.headers
                val headerStrings = headers.names().map { name -> "$name: ${headers[name]}" }
                config.logger.log("Request headers for ${request.url}: ${headerStrings.joinToString(", ")}")
            } catch (e: Throwable) {
                // ignore
            }
        }
    }
}
