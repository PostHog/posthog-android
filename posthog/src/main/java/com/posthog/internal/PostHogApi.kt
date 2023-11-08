package com.posthog.internal

import com.google.gson.Gson
import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.io.OutputStream

/**
 * The class that calls the PostHog API
 * @property config the Config
 * @property dateProvider the Date provider
 */
internal class PostHogApi(
    private val config: PostHogConfig,
    private val dateProvider: PostHogDateProvider,
) {
    private val mediaType by lazy {
        try {
            // can throw IllegalArgumentException
            "application/json; charset=utf-8".toMediaType()
        } catch (ignored: Throwable) {
            null
        }
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(GzipRequestInterceptor(config))
        .build()

    private val theHost: String
        get() {
            return if (config.host.endsWith("/")) config.host.substring(0, config.host.length - 1) else config.host
        }

    @Throws(PostHogApiError::class, IOException::class)
    fun batch(events: List<PostHogEvent>) {
        val batch = PostHogBatchEvent(config.apiKey, events)

        val request = makeRequest("$theHost/batch") {
            batch.sentAt = dateProvider.currentDate()
            config.serializer.serialize(batch, it.bufferedWriter())
        }

        client.newCall(request).execute().use {
            if (!it.isSuccessful) throw PostHogApiError(it.code, it.message, it.body)
        }
    }

    @Throws(PostHogApiError::class, IOException::class)
    fun snapshot(event: PostHogEvent) {
        event.apiKey = config.apiKey
        @Suppress("UNCHECKED_CAST")
        val props = event.properties?.get("\$snapshot_data") as? List<Any> ?: listOf()

        config.serializer.serializeObject(props)?.let {
            print("rrweb event: $it")
        }

        val events = listOf(event)
        // TODO: read endpoint from decide API
        // missing sent_at?
        val request = makeRequest("$theHost/s") {
            config.serializer.serialize(events, it.bufferedWriter())
        }

        client.newCall(request).execute().use {
            if (!it.isSuccessful) throw PostHogApiError(it.code, it.message, it.body)
        }
    }

    private fun makeRequest(url: String, serializer: (outputStream: OutputStream) -> Unit): Request {
        val requestBody = object : RequestBody() {
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
        anonymousId: String,
        groups: Map<String, Any>?,
    ): PostHogDecideResponse? {
        val decideRequest = PostHogDecideRequest(config.apiKey, distinctId, anonymousId, groups)

        val request = makeRequest("$theHost/decide/?v=3") {
            config.serializer.serialize(decideRequest, it.bufferedWriter())
        }

        client.newCall(request).execute().use {
            if (!it.isSuccessful) throw PostHogApiError(it.code, it.message, it.body)

            it.body?.let { body ->
                return config.serializer.deserialize(body.charStream().buffered())
            }
            return null
        }
    }
}
