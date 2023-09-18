package com.posthog.internal

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.OutputStream
import java.util.Date

internal class PostHogApi(private val config: PostHogConfig) {
    // TODO: DEFAULT_READ_TIMEOUT_MILLIS, DEFAULT_CONNECT_TIMEOUT_MILLIS
    private val client = OkHttpClient.Builder()
        .addInterceptor(GzipRequestInterceptor(config))
        .build()

    // can throw IllegalArgumentException
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val gson = GsonBuilder().apply {
        registerTypeAdapter(Date::class.java, GsonDateTypeAdapter(config))
            .setLenient()
    }.create()
    private val gsonBatchBodyType = object : TypeToken<PostHogBatchEvent>() {}.type
    private val gsonDecideBodyType = object : TypeToken<Map<String, Any>>() {}.type

    // TODO: do we care about max queue size? apparently theres a 500kb hardlimit on the server

    fun batch(events: List<PostHogEvent>) {
        val batch = PostHogBatchEvent(config.apiKey, events)
//        """
// {
//  "api_key": "_6SG-F7I1vCuZ-HdJL3VZQqjBlaSb1_20hDPwqMNnGI",
//  "batch": [
//    {
//      "event": "testEvent",
//      "properties": {
//        "testProperty": "testValue"
//      },
//      "timestamp": "2023-09-13T12:05:30.326Z"
//    }
//  ],
//  "timestamp": "2023-09-13T12:05:30.326Z",
//  "sent_at": "2023-09-13T12:05:30.326Z",
// }
//        """.trimIndent()
        // TODO: MAX_PAYLOAD_SIZE, MAX_BATCH_SIZE
        val request = makeRequest("${config.host}/batch") {
            val writer = it.bufferedWriter()
            batch.sentAt = Date()
            gson.toJson(batch, gsonBatchBodyType, writer)
            writer.flush()
        }

        client.newCall(request).execute().use {
            if (!it.isSuccessful) throw PostHogApiError(it.code, it.message, body = it.body)
//            """
// {
//  "status": 1
// }
//            """.trimIndent()
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

    fun decide(properties: Map<String, Any>): Map<String, Any>? {
        val map = mutableMapOf<String, Any>()
        map.putAll(properties)
        map["api_key"] = config.apiKey
//        """
// {
//  "distinct_id": "1fc77c1a-5f98-43b3-bb77-7a2dd15fd13a",
//  "api_key": "_6SG-F7I1vCuZ-HdJL3VZQqjBlaSb1_20hDPwqMNnGI"
// }
//        """.trimIndent()
        val request = makeRequest("${config.host}/decide/?v=3") {
            val writer = it.bufferedWriter()
            gson.toJson(map, gsonDecideBodyType, writer)
            writer.flush()
        }

        client.newCall(request).execute().use {
            // TODO: do we handle 429 differently?
            if (!it.isSuccessful) throw PostHogApiError(it.code, it.message, body = it.body)

            it.body?.let { body ->
                return gson.fromJson(body.charStream().buffered(), gsonDecideBodyType)
            }
            return null
        }
//            """
// {
//  "config": {
//    "enable_collect_everything": true
//  },
//  "toolbarParams": {},
//  "isAuthenticated": false,
//  "supportedCompression": [
//    "gzip",
//    "gzip-js"
//  ],
//  "featureFlags": {
//    "4535-funnel-bar-viz": true
//  },
//  "sessionRecording": false,
//  "errorsWhileComputingFlags": false,
//  "featureFlagPayloads": {},
//  "capturePerformance": true,
//  "autocapture_opt_out": false,
//  "autocaptureExceptions": false,
//  "siteApps": [
//    {
//      "id": 21039,
//      "url": "/site_app/21039/EOsOSePYNyTzHkZ3f4mjrjUap8Hy8o2vUTAc6v1ZMFP/576ac89bc8aed72a21d9b19221c2c626/"
//    }
//  ]
// }
//            """.trimIndent()
    }
}
