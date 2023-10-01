package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.io.OutputStream
import java.util.Date

internal class PostHogApi(
    private val config: PostHogConfig,
    private val serializer: PostHogSerializer,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(GzipRequestInterceptor(config))
        .build(),
) {
    private val mediaType by lazy {
        try {
            // can throw IllegalArgumentException
            "application/json; charset=utf-8".toMediaType()
        } catch (ignored: Throwable) {
            null
        }
    }

    @Throws(PostHogApiError::class, IOException::class)
    fun batch(events: List<PostHogEvent>) {
        val batch = PostHogBatchEvent(config.apiKey, events)

        val request = makeRequest("${config.host}/batch") {
            batch.sentAt = Date()
            serializer.serializeBatchApi(batch, it.bufferedWriter())
        }

        client.newCall(request).execute().use {
            if (!it.isSuccessful) throw PostHogApiError(it.code, it.message, body = it.body)
        }
    }

    private fun makeRequest(url: String, serializer: (outputStream: OutputStream) -> Unit): Request {
        val requestBody = object : RequestBody() {
            override fun contentType() = mediaType

            override fun writeTo(sink: BufferedSink) {
                serializer(sink.outputStream())
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
        anonymousId: String,
        groups: Map<String, Any>? = null,
    ): Map<String, Any>? {
        val decideRequest = PostHogDecideRequest(config.apiKey, distinctId, anonymousId, groups = groups)

        val request = makeRequest("${config.host}/decide/?v=3") {
            serializer.serializeDecideApi(decideRequest, it.bufferedWriter())
        }

        client.newCall(request).execute().use {
            if (!it.isSuccessful) throw PostHogApiError(it.code, it.message, body = it.body)

            it.body?.let { body ->
                return serializer.deserializeDecideApi(body.charStream().buffered())
            }
            return null
        }
    }
}
