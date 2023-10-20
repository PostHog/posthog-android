package com.posthog.internal

import com.posthog.PostHogAttachment
import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

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
        .build()

    private val theHost: String
        get() {
            return if (config.host.endsWith("/")) config.host.substring(0, config.host.length - 1) else config.host
        }

    @Throws(PostHogApiError::class, IOException::class)
    fun batch(events: List<PostHogEvent>) {
        val batch = PostHogBatchEvent(config.apiKey, events, sentAt = dateProvider.currentDate())

        val json = config.serializer.serializeObject(batch)!!
        val body = json.toRequestBody(mediaType)
        val request = Request.Builder()
            .url("$theHost/batch")
            .header("User-Agent", config.userAgent)
            .header("Content-Length", "${body.contentLength()}")
            .post(body)
            .build()

        client.newCall(request).execute().use {
            if (!it.isSuccessful) throw PostHogApiError(it.code, it.message, it.body)
        }
    }

    @Throws(PostHogApiError::class, IOException::class)
    fun decide(
        distinctId: String,
        anonymousId: String,
        groups: Map<String, Any>?,
    ): PostHogDecideResponse? {
        val decideRequest = PostHogDecideRequest(config.apiKey, distinctId, anonymousId, groups)

        val json = config.serializer.serializeObject(decideRequest)!!
        val body = json.toRequestBody(mediaType)
        val request = Request.Builder()
            .url("$theHost/decide/?v=3")
            .header("User-Agent", config.userAgent)
            .header("Content-Length", "${body.contentLength()}")
            .post(body)
            .build()

        client.newCall(request).execute().use {
            if (!it.isSuccessful) throw PostHogApiError(it.code, it.message, it.body)

            it.body?.let { body ->
                return config.serializer.deserialize(body.charStream().buffered())
            }
            return null
        }
    }

    @Throws(PostHogApiError::class, IOException::class, OutOfMemoryError::class)
    fun attachment(eventId: UUID, attachment: PostHogAttachment): PostHogAttachmentResponse? {
        println(eventId)

        val bytes = attachment.file.readBytes()
        val length = bytes.size
        val request = Request.Builder()
            .url("$theHost/api/attachments/upload?api_key=${config.apiKey}")
            .header("User-Agent", config.userAgent)
            .header("Content-Length", "$length")
            .header("Content-Disposition", "attachment; filename=${attachment.file.name}")
            .post(bytes.toRequestBody(attachment.contentType.toMediaType()))
            .build()

        client.newCall(request).execute().use {
            if (!it.isSuccessful) throw PostHogApiError(it.code, it.message, it.body)

            it.body?.let { body ->
                return config.serializer.deserialize(body.charStream().buffered())
            }
            return null
        }
    }
}
