package com.posthog.internal

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
    private val gsonBatchBodyType = object : TypeToken<PostHogBatchEvent>() {}.type
    private val gsonDecideBodyType = object : TypeToken<Map<String, Any>>() {}.type

    fun batch(events: List<PostHogEvent>) {
        val batch = PostHogBatchEvent(config.apiKey, events)
        val json = gson.toJson(batch, gsonBatchBodyType)
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
//  "timestamp": "2023-09-13T12:05:30.326Z"
// }
//        """.trimIndent()
        val request = makeRequest(json, "${config.host}/batch")

        client.newCall(request).execute().use {
            if (!it.isSuccessful) throw PostHogApiError(it.code, it.message, body = it.body)
//            """
// {
//  "status": 1
// }
//            """.trimIndent()
        }
    }

    private fun makeRequest(json: String, url: String): Request {
        val body = json.toRequestBody(mediaType)

        return Request.Builder()
            .url(url)
            .header("User-Agent", config.userAgent)
            .post(body)
            .build()
    }

    fun decide(properties: Map<String, Any>): Map<String, Any>? {
        val map = mutableMapOf<String, Any>()
        map.putAll(properties)
        map["api_key"] = config.apiKey

        val json = gson.toJson(map, gsonDecideBodyType)
//        """
// {
//  "distinct_id": "1fc77c1a-5f98-43b3-bb77-7a2dd15fd13a",
//  "api_key": "_6SG-F7I1vCuZ-HdJL3VZQqjBlaSb1_20hDPwqMNnGI"
// }
//        """.trimIndent()
        val request = makeRequest(json, "${config.host}/decide/?v=3")

        client.newCall(request).execute().use {
            if (!it.isSuccessful) throw PostHogApiError(it.code, it.message, body = it.body)

            it.body?.let { body ->
                return gson.fromJson(body.string(), gsonDecideBodyType)
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
