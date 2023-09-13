package com.posthog

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Date

internal class PostHogApi(private val config: PostHogConfig) {
    private val client = OkHttpClient.Builder()
        .addInterceptor(GzipRequestInterceptor(config))
        .build()

    // can throw IllegalArgumentException
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val gson = GsonBuilder().apply {
        registerTypeAdapter(Date::class.java, GsonDateTypeAdapter(config))
    }.create()
    private val gsonBodyType = object : TypeToken<PostHogBatchEvent>() {}.type

    fun batch(events: List<PostHogEvent>) {
        val batch = PostHogBatchEvent(config.apiKey, events)
        val json = gson.toJson(batch, gsonBodyType) // {"api_key":"_6SG-F7I1vCuZ-HdJL3VZQqjBlaSb1_20hDPwqMNnGI","batch":[{"event":"testEvent","properties":{"testProperty":"testValue"},"timestamp":"2023-09-13T12:05:30.326Z"}],"timestamp":"2023-09-13T12:05:30.326Z"}

        val body = json.toRequestBody(mediaType)

        val request = Request.Builder()
            .url("${config.host}/batch")
            .header("User-Agent", config.userAgent)
            .post(body)
            .build()

        client.newCall(request).execute().use {
            if (!it.isSuccessful) throw IOException("Unexpected code $it")
            //                {"status": 1} - success
// TODO: rete limit will be part of response in the future
        }
    }

    // TODO: decide, get APIs
}
