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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType
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
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

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
        private const val APP_JSON = "application/json"
        private const val FLAGS_INITIAL_RETRY_DELAY_MS = 300L
        private const val FLAGS_MAX_RETRY_DELAY_MS = 30_000L
        private const val ERROR_BODY_PEEK_BYTES = 2048L
        private const val CAPTURE_V1_ANALYTICS_PATH = "/i/v1/analytics/events"
        private const val CAPTURE_V1_AI_PATH = "/i/v1/ai/events"
        private const val CAPTURE_V1_MAX_REDIRECTS = 5
        private const val CAPTURE_V1_MAX_PENDING_RETRIES = 16
        private const val CAPTURE_V1_MAX_RESULTS_BYTES = 1024L * 1024L
    }

    private val mediaType by lazy {
        try {
            // can throw IllegalArgumentException
            APP_JSON_UTF_8.toMediaType()
        } catch (ignored: Throwable) {
            null
        }
    }

    // A 404 keeps the events queued, so the warning is printed once instead of on every retry.
    private val warnedCaptureNotFound = AtomicBoolean(false)

    private val captureMediaType by lazy {
        try {
            APP_JSON.toMediaType()
        } catch (ignored: Throwable) {
            null
        }
    }

    // Caps the whole results read, since a body that trickles bytes never hits the read timeout
    // and would hold the queue thread. Internal so tests can shorten it.
    internal var captureV1ResultsReadDeadlineMs: Long = 10_000L

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

    // Capture v1 decides its own redirects (see captureV1): OkHttp would follow any 3xx, drop the
    // Authorization header on a cross-host hop and turn a 302 into a body-less GET. The queue owns
    // retries too: an OkHttp resend would repeat PostHog-Attempt and PostHog-Request-Timestamp.
    // OkHttp also resends a 503 with `Retry-After: 0` whatever retryOnConnectionFailure says, so
    // that header is dropped below its retry layer; the queue waits the same with or without it.
    private val captureClient: OkHttpClient by lazy {
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .addNetworkInterceptor(
                object : Interceptor {
                    override fun intercept(chain: Interceptor.Chain): Response {
                        val response = chain.proceed(chain.request())
                        return if (response.code == 503 && response.header("Retry-After")?.toIntOrNull() == 0) {
                            response.newBuilder().removeHeader("Retry-After").build()
                        } else {
                            response
                        }
                    }
                },
            )
            .build()
    }

    // AI batches can be several MB, so they get their own timeout and compression setting.
    private val aiCaptureClient: OkHttpClient by lazy {
        val timeoutSeconds = config.effectiveCaptureAiTimeoutSeconds.toLong()
        val builder =
            // Idle timeouts only: a multi-MB upload that is still making progress on a slow link
            // must not be cut off by a whole-call deadline.
            captureClient.newBuilder()
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
        val interceptors = builder.interceptors()
        for (index in interceptors.indices) {
            if (interceptors[index] is GzipRequestInterceptor) {
                interceptors[index] = GzipRequestInterceptor(config) { config.captureAiCompression }
            }
        }
        builder.build()
    }

    private val captureV1Lock = Any()

    // The identity of each capture v1 request whose events are still to be retried, keyed by endpoint
    // path and uuid set, so a send of other events in between keeps it. Holds the most recent
    // CAPTURE_V1_MAX_PENDING_RETRIES: an entry stays when the queue drops its events instead of
    // retrying them. In memory only: a new request id after an app restart is fine, the server only logs it.
    private val captureV1PendingRetry =
        object : LinkedHashMap<Pair<String, Set<String>>, CaptureV1RequestIdentity>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Set<String>>, CaptureV1RequestIdentity>?): Boolean =
                size > CAPTURE_V1_MAX_PENDING_RETRIES
        }

    private val theHost: String
        get() {
            return if (config.host.endsWith("/")) config.host.substring(0, config.host.length - 1) else config.host
        }

    @Throws(PostHogApiError::class, IOException::class)
    public fun batch(events: List<PostHogEvent>) {
        val batch = PostHogBatchEvent(config.projectToken, events)

        val url = "$theHost/batch"
        val request =
            makeRequest(url) {
                batch.sentAt = config.dateProvider.currentDate()

                logRequest(batch, url)

                config.serializer.serialize(batch, it.bufferedWriter())
            }

        executeNoBody(request)
    }

    /**
     * Sends analytics events to capture v1 (`POST /i/v1/analytics/events`).
     *
     * Returns the uuids of the events the 2xx response asked to retry, with the `Retry-After` it
     * sent; every other event in [events] was delivered or dropped and must not be sent again.
     *
     * A 2xx whose body can't be read as `{"results": {...}}`, including a transfer that failed after
     * the status arrived, is a terminal failure of the whole batch: it throws a [PostHogApiError]
     * with that 2xx status, which the queue doesn't retry.
     *
     * Only a 307 or 308 to the origin of [PostHogConfig.host] is followed, at most 5 times, with
     * the same body and headers; any other redirect is a terminal [PostHogApiError].
     *
     * Throws [PostHogApiError] for a non-2xx status or unreadable results, and [IOException] for a
     * transport failure before the status.
     */
    @Throws(PostHogApiError::class, IOException::class)
    internal fun captureV1(events: List<PostHogEvent>): SendResult = postCaptureV1(CAPTURE_V1_ANALYTICS_PATH, events, captureClient)

    /**
     * Sends AI events to the capture v1 AI endpoint (`POST /i/v1/ai/events`). Same wire format,
     * per-event results, redirect rule and retry identity (kept per path) as [captureV1], with
     * [PostHogConfig.captureAiTimeout] and [PostHogConfig.captureAiCompression].
     */
    @Throws(PostHogApiError::class, IOException::class)
    internal fun captureAi(events: List<PostHogEvent>): SendResult = postCaptureV1(CAPTURE_V1_AI_PATH, events, aiCaptureClient)

    @Throws(PostHogApiError::class, IOException::class)
    private fun postCaptureV1(
        path: String,
        events: List<PostHogEvent>,
        client: OkHttpClient,
    ): SendResult {
        val uuids = events.mapNotNull { it.uuid?.toString() }
        val identity = nextCaptureV1Identity(path, uuids.toSet())
        val body =
            mapOf(
                "created_at" to identity.createdAt,
                "batch" to events.map { captureV1Json(it, config.logger) },
            )

        val url = "$theHost$path"
        var request =
            makeRequest(url, contentType = captureMediaType) {
                // Events hold user data, and AI events prompts and media, so the debug log names only the count.
                config.logger.log("Request $url: ${events.size} events")

                config.serializer.serialize(body, it.bufferedWriter())
            }.newBuilder()
                .header("Authorization", "Bearer ${config.projectToken}")
                .header("PostHog-Sdk-Info", config.userAgent)
                .header("PostHog-Attempt", identity.attempt.toString())
                .header("PostHog-Request-Id", identity.requestId)
                .header("PostHog-Request-Timestamp", formatISO8601Date(config.dateProvider.currentDate()))
                .build()

        var redirects = 0
        while (true) {
            logRequestHeaders(request)

            client.newCall(request).execute().use { response ->
                // The status only: the results name event uuids.
                config.logger.log("Response ${response.request.url}: ${response.code}")

                if (response.code == 404 && warnedCaptureNotFound.compareAndSet(false, true)) {
                    // Warnings log even with debug off, so drop any credentials or query the host carries.
                    val safeUrl =
                        response.request.url.newBuilder()
                            .username("")
                            .password("")
                            .query(null)
                            .fragment(null)
                            .build()
                    config.logger.logWarning(
                        "$safeUrl returned 404. Events stay queued and are retried. " +
                            "If you use a reverse proxy or self-host PostHog, make sure it serves $path.",
                    )
                }
                if (response.code != 307 && response.code != 308) {
                    return captureV1Result(response, path, events, identity)
                }

                val target = response.header("Location")?.let { location -> request.url.resolve(location) }
                if (redirects >= CAPTURE_V1_MAX_REDIRECTS || target == null || !isConfiguredOrigin(target)) {
                    config.logger.log("Capture did not follow a ${response.code} redirect.")
                    clearCaptureV1Retry(path, identity)
                    throw PostHogApiError(response.code, response.message, response.body, parseRetryAfter(response))
                }
                // Same origin, so the body and every header are safe to resend.
                request = request.newBuilder().url(target).build()
                redirects++
            }
        }
    }

    /**
     * Reuses the request id and `created_at` with the next attempt number when [uuids] is the set
     * of events the previous request to [path] left to retry; otherwise starts a new request at
     * attempt 1. The identity is kept until the outcome is known, so a transport failure or a
     * retriable status resends the whole batch under it.
     */
    private fun nextCaptureV1Identity(
        path: String,
        uuids: Set<String>,
    ): CaptureV1RequestIdentity {
        synchronized(captureV1Lock) {
            val pending = captureV1PendingRetry[path to uuids]
            val identity =
                if (pending != null) {
                    pending.copy(attempt = pending.attempt + 1)
                } else {
                    CaptureV1RequestIdentity(
                        uuids = uuids,
                        requestId = UUID.randomUUID().toString(),
                        attempt = 1,
                        createdAt = formatISO8601Date(config.dateProvider.currentDate()),
                    )
                }
            captureV1PendingRetry[path to uuids] = identity
            return identity
        }
    }

    private fun clearCaptureV1Retry(
        path: String,
        identity: CaptureV1RequestIdentity,
    ) {
        synchronized(captureV1Lock) {
            captureV1PendingRetry.remove(path to identity.uuids)
        }
    }

    /**
     * Reads the per-event results of a 2xx and records which events, if any, the next request
     * retries under the same identity. A non-2xx is thrown; a terminal one also drops the identity,
     * as does a 2xx without readable results, which is thrown as a terminal failure.
     */
    @Throws(PostHogApiError::class)
    private fun captureV1Result(
        response: Response,
        path: String,
        events: List<PostHogEvent>,
        identity: CaptureV1RequestIdentity,
    ): SendResult {
        if (!response.isSuccessful) {
            if (!isCaptureV1RetriableStatusCode(response.code)) {
                clearCaptureV1Retry(path, identity)
            }
            throw PostHogApiError(response.code, response.message, response.body, parseRetryAfter(response))
        }

        // Counts only: the body may name event uuids.
        val results = readCaptureV1Results(response)
        if (results == null) {
            clearCaptureV1Retry(path, identity)
            val message = "Capture returned ${response.code} without readable per-event results, dropping ${events.size} events."
            config.logger.log(message)
            throw PostHogApiError(response.code, message, null)
        }

        val retryUuids = mutableSetOf<String>()
        var dropped = 0
        var duplicatesDropped = 0
        // Events missing from the results count as delivered. The uuids are read once, so a repeated
        // uuid's later copies (`duplicates_dropped`) are counted, not given the kept copy's verdict.
        for (uuid in events.mapNotNullTo(LinkedHashSet()) { it.uuid?.toString() }) {
            val entry = results[uuid] as? Map<*, *> ?: continue
            when (entry["result"]) {
                "retry" -> retryUuids.add(uuid)
                "drop" -> dropped++
            }
            // Clamped to the batch size, so a malformed count can't overflow the sum.
            duplicatesDropped += (entry["duplicates_dropped"] as? Number)?.toLong()?.coerceIn(0L, events.size.toLong())?.toInt() ?: 0
        }

        // Counts only: one line per batch, never per event.
        if (dropped > 0 || duplicatesDropped > 0 || retryUuids.isNotEmpty()) {
            config.logger.log(
                "Capture: $dropped dropped, $duplicatesDropped duplicate copies dropped, ${retryUuids.size} to retry.",
            )
        }

        synchronized(captureV1Lock) {
            captureV1PendingRetry.remove(path to identity.uuids)
            if (retryUuids.isNotEmpty()) {
                captureV1PendingRetry[path to retryUuids] = identity.copy(uuids = retryUuids)
            }
        }
        return SendResult(retryUuids, parseRetryAfter(response))
    }

    private fun readCaptureV1Results(response: Response): Map<*, *>? {
        return try {
            // Bounded: an oversized or endless body counts as unreadable instead of filling the heap.
            val source = response.body?.source() ?: return null
            source.timeout().deadline(captureV1ResultsReadDeadlineMs, TimeUnit.MILLISECONDS)
            if (source.request(CAPTURE_V1_MAX_RESULTS_BYTES + 1)) return null
            val body = source.buffer.readUtf8()
            (config.serializer.deserializeString(body) as? Map<*, *>)?.get("results") as? Map<*, *>
        } catch (e: Throwable) {
            null
        }
    }

    private fun isConfiguredOrigin(url: HttpUrl): Boolean {
        val host = config.host.toHttpUrlOrNull() ?: return false
        return url.scheme == host.scheme && url.host.equals(host.host, ignoreCase = true) && url.port == host.port
    }

    @Throws(PostHogApiError::class, IOException::class)
    public fun snapshot(events: List<PostHogEvent>) {
        events.forEach {
            it.apiKey = config.projectToken
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
     * `/i/v1/logs?token=<projectToken>`. Throws [PostHogApiError] on a non-success
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

        val url = "$theHost/i/v1/logs?token=${config.projectToken}"
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
        appId: String,
        identityToken: String? = null,
    ): Unit = sendPushSubscription("POST", distinctId, deviceToken, appId, identityToken)

    @Throws(PostHogApiError::class, IOException::class)
    public fun pushUnsubscription(
        distinctId: String,
        deviceToken: String,
        appId: String,
        identityToken: String? = null,
    ): Unit = sendPushSubscription("DELETE", distinctId, deviceToken, appId, identityToken)

    @Throws(PostHogApiError::class, IOException::class)
    private fun sendPushSubscription(
        method: String,
        distinctId: String,
        deviceToken: String,
        appId: String,
        identityToken: String?,
    ) {
        val pushSubscription =
            PostHogPushSubscriptionRequest(
                projectToken = config.projectToken,
                distinctId = distinctId,
                deviceToken = deviceToken,
                appId = appId,
                identityToken = identityToken,
            )

        val url = "$theHost/api/push_subscriptions/"
        val request =
            makeRequest(url, method = method) {
                // Redact the identity token: it is a short-lived bearer credential and logRequest
                // writes the whole body to the debug logger (Logcat on Android). CWE-532.
                logRequest(
                    pushSubscription.copy(identityToken = identityToken?.let { "<redacted>" }),
                    url,
                )

                config.serializer.serialize(pushSubscription, it.bufferedWriter())
            }

        executeNoBody(request, readErrorCode = true)
    }

    @Throws(PostHogApiError::class, IOException::class)
    private fun executeNoBody(
        request: Request,
        readErrorCode: Boolean = false,
    ) {
        logRequestHeaders(request)

        client.newCall(request).execute().use {
            val response = logResponse(it)

            if (!response.isSuccessful) {
                val errorCode = if (readErrorCode) parseErrorCode(response) else null
                throw PostHogApiError(response.code, response.message, response.body, parseRetryAfter(response), errorCode)
            }
        }
    }

    // The status code alone cannot separate a rejection the app can recover from (a stale identity
    // token) from one it cannot (a project token that resolves to nothing): both are 401.
    private fun parseErrorCode(response: Response): String? {
        return try {
            val body = response.peekBody(ERROR_BODY_PEEK_BYTES).string()
            (config.serializer.deserializeString(body) as? Map<*, *>)?.get("code") as? String
        } catch (e: Throwable) {
            null
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
        method: String = "POST",
        contentType: MediaType? = mediaType,
        serializer: (outputStream: OutputStream) -> Unit,
    ): Request {
        val requestBody =
            object : RequestBody() {
                override fun contentType() = contentType

                override fun writeTo(sink: BufferedSink) {
                    sink.outputStream().use {
                        serializer(it)
                    }
                }
            }

        val builder =
            Request.Builder()
                .url(url)
                .header("User-Agent", config.userAgent)
        return when (method) {
            "DELETE" -> builder.delete(requestBody)
            else -> builder.post(requestBody)
        }.build()
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
                config.projectToken,
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
                    error is UnknownHostException ||
                    error is SSLException ||
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
                .url("$host/array/${config.projectToken}/config")
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
        val url = "$theHost/api/feature_flag/local_evaluation/?token=${config.projectToken}&send_cohorts"

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

/**
 * Identity of a capture v1 request, kept so a retry of the same events reuses
 * `PostHog-Request-Id` and `created_at` with an incremented `PostHog-Attempt`.
 */
internal class CaptureV1RequestIdentity(
    val uuids: Set<String>,
    val requestId: String,
    val attempt: Int,
    val createdAt: String,
) {
    fun copy(
        uuids: Set<String> = this.uuids,
        attempt: Int = this.attempt,
    ): CaptureV1RequestIdentity = CaptureV1RequestIdentity(uuids, requestId, attempt, createdAt)
}

/** Legacy properties and the capture v1 option each one fills. */
internal val LEGACY_OPTION_PROPERTIES: List<Pair<String, String>> =
    listOf(
        "\$cookieless_mode" to "cookieless_mode",
        "\$ignore_sent_at" to "disable_skew_correction",
        "\$product_tour_id" to "product_tour_id",
        "\$process_person_profile" to "process_person_profile",
    )

/**
 * Shapes a stored event for capture v1. `$session_id` and `$window_id` move to the event root, and
 * `$lib`/`$lib_version` are dropped because the server reads them from `PostHog-Sdk-Info`.
 *
 * Legacy option properties are hoisted here, after `beforeSend`, so events queued by older versions
 * are hoisted too. Each is always removed from properties and fills its option only when the option
 * is missing or null. Values aren't coerced: the server validates them.
 */
internal fun captureV1Json(
    event: PostHogEvent,
    logger: PostHogLogger,
): Map<String, Any?> {
    val properties = LinkedHashMap<String, Any?>(event.properties ?: emptyMap())
    properties.remove("\$lib")
    properties.remove("\$lib_version")

    val options = LinkedHashMap<String, Any?>(event.options ?: emptyMap())
    for ((property, option) in LEGACY_OPTION_PROPERTIES) {
        if (!properties.containsKey(property)) continue
        val legacy = properties.remove(property)
        if (options[option] == null) {
            options[option] = legacy
        }
    }

    val json = LinkedHashMap<String, Any?>()
    json["event"] = event.event
    json["uuid"] = event.uuid?.toString()
    json["distinct_id"] = event.distinctId
    json["timestamp"] = formatISO8601Date(event.timestamp)
    liftStringProperty("\$session_id", properties, logger)?.let { json["session_id"] = it }
    liftStringProperty("\$window_id", properties, logger)?.let { json["window_id"] = it }
    if (options.isNotEmpty()) {
        json["options"] = options
    }
    json["properties"] = properties
    return json
}

/**
 * Removes [key] from [properties] and returns its value when it's a string, including an empty one,
 * which is sent unchanged. Capture rejects the whole request when a root `session_id` or `window_id`
 * isn't a string, so any other value is dropped with a warning that names its JSON type, never the
 * value. A null value counts as unset and is dropped without a warning.
 */
internal fun liftStringProperty(
    key: String,
    properties: MutableMap<String, Any?>,
    logger: PostHogLogger,
): String? {
    if (!properties.containsKey(key)) return null
    val value = properties.remove(key) ?: return null
    if (value is String) return value
    logger.log("dropping $key: a ${jsonTypeName(value)} value is not a string")
    return null
}

/**
 * Whether capture reads [value] as a false flag option: `false`, a zero number, or a string such as
 * `"false"`, `"no"`, `"off"` or `"0"`, ignoring case and surrounding spaces.
 */
internal fun isFalseOptionValue(value: Any?): Boolean {
    return when (value) {
        is Boolean -> !value
        is Number -> value.toDouble() == 0.0
        is String -> {
            val text = value.trim().lowercase()
            text in setOf("false", "f", "no", "n", "off") || text.toDoubleOrNull() == 0.0
        }
        else -> false
    }
}

private fun jsonTypeName(value: Any): String {
    return when (value) {
        is Boolean -> "bool"
        is Number -> "number"
        is String -> "string"
        is Map<*, *> -> "object"
        is Iterable<*>, is Array<*> -> "array"
        else -> value.javaClass.simpleName
    }
}
