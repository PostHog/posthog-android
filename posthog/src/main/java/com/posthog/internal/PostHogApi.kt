package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogConfig.Companion.DEFAULT_EU_ASSETS_HOST
import com.posthog.PostHogConfig.Companion.DEFAULT_EU_HOST
import com.posthog.PostHogConfig.Companion.DEFAULT_US_ASSETS_HOST
import com.posthog.PostHogConfig.Companion.DEFAULT_US_HOST
import com.posthog.PostHogEvent
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
internal class PostHogApi(
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
        OkHttpClient.Builder()
            .proxy(config.proxy)
            .addInterceptor(GzipRequestInterceptor(config))
            .build()

    private val theHost: String
        get() {
            return if (config.host.endsWith("/")) config.host.substring(0, config.host.length - 1) else config.host
        }

    @Throws(PostHogApiError::class, IOException::class)
    fun batch(events: List<PostHogEvent>) {
        val batch = PostHogBatchEvent(config.apiKey, events)

        val url = "$theHost/batch"
        val request =
            makeRequest(url) {
                batch.sentAt = config.dateProvider.currentDate()

                logRequest(batch, url)

                config.serializer.serialize(batch, it.bufferedWriter())
            }

        client.newCall(request).execute().use {
            val response = logResponse(it)

            if (!response.isSuccessful) throw PostHogApiError(response.code, response.message, response.body)
        }
    }

    @Throws(PostHogApiError::class, IOException::class)
    fun snapshot(events: List<PostHogEvent>) {
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
    fun decide(
        distinctId: String,
        anonymousId: String?,
        groups: Map<String, String>?,
    ): PostHogDecideResponse? {
        val decideRequest = PostHogDecideRequest(config.apiKey, distinctId, anonymousId = anonymousId, groups)

        val url = "$theHost/decide/?v=4"
        logRequest(decideRequest, url)

        val request =
            makeRequest(url) {
                config.serializer.serialize(decideRequest, it.bufferedWriter())
            }

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
    fun remoteConfig(): PostHogRemoteConfigResponse? {
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

        client.newCall(request).execute().use {
            val response = logResponse(it)

            if (!response.isSuccessful) throw PostHogApiError(response.code, response.message, response.body)

            response.body?.let { body ->
                return config.serializer.deserialize(body.charStream().buffered())
            }
            return null
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
}
