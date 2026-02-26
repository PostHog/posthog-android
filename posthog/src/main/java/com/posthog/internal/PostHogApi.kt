package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogConfig.Companion.DEFAULT_EU_ASSETS_HOST
import com.posthog.PostHogConfig.Companion.DEFAULT_EU_HOST
import com.posthog.PostHogConfig.Companion.DEFAULT_US_ASSETS_HOST
import com.posthog.PostHogConfig.Companion.DEFAULT_US_HOST
import com.posthog.PostHogEvent
import com.posthog.PostHogInternal
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSink
import java.io.IOException
import java.io.OutputStream

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
            .build()

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

        logRequestHeaders(request)

        client.newCall(request).execute().use {
            val response = logResponse(it)

            if (!response.isSuccessful) throw PostHogApiError(response.code, response.message, response.body)
        }
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

        logRequestHeaders(request)

        client.newCall(request).execute().use {
            val response = logResponse(it)

            if (!response.isSuccessful) throw PostHogApiError(response.code, response.message, response.body)
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
        groups: Map<String, String>? = null,
        personProperties: Map<String, Any?>? = null,
        groupProperties: Map<String, Map<String, Any?>>? = null,
    ): PostHogFlagsResponse? {
        val flagsRequest =
            PostHogFlagsRequest(
                config.apiKey,
                distinctId,
                anonymousId = anonymousId,
                groups,
                personProperties,
                groupProperties,
                config.evaluationContexts,
            )

        val url = "$theHost/flags/?v=2"
        logRequest(flagsRequest, url)

        val request =
            makeRequest(url) {
                config.serializer.serialize(flagsRequest, it.bufferedWriter())
            }

        logRequestHeaders(request)

        client.newCall(request).execute().use {
            val response = logResponse(it)

            if (!response.isSuccessful) throw PostHogApiError(response.code, response.message, response.body)

            response.body?.let { body ->
                return config.serializer.deserialize(body.charStream().buffered())
            }
            return null
        }
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
