package com.posthog.internal

import com.google.gson.GsonBuilder
import com.google.gson.JsonIOException
import com.google.gson.reflect.TypeToken
import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import java.io.Reader
import java.io.Writer
import java.util.Date

internal class PostHogSerializer(private val config: PostHogConfig) {
    private val gson = GsonBuilder().apply {
        registerTypeAdapter(Date::class.java, GsonDateTypeAdapter(config))
            .setLenient()
    }.create()
    private val gsonBatchType = object : TypeToken<PostHogBatchEvent>() {}.type
    private val gsonEventType = object : TypeToken<PostHogEvent>() {}.type
    private val gsonDecideType = object : TypeToken<Map<String, Any>>() {}.type

    @Throws(JsonIOException::class)
    fun serializeEvent(event: PostHogEvent, writer: Writer) {
        gson.toJson(event, gsonEventType, writer)
        writer.flush()
    }

    @Throws(JsonIOException::class)
    fun deserializeEvent(reader: Reader): PostHogEvent? {
        return gson.fromJson(reader, gsonEventType)
    }

    @Throws(JsonIOException::class)
    fun serializeDecideApi(properties: Map<String, Any>, writer: Writer) {
        gson.toJson(properties, gsonDecideType, writer)
        writer.flush()
    }

    @Throws(JsonIOException::class)
    fun deserializeDecideApi(reader: Reader): Map<String, Any>? {
        return gson.fromJson(reader, gsonDecideType)
    }

    @Throws(JsonIOException::class)
    fun serializeBatchApi(batch: PostHogBatchEvent, writer: Writer) {
        gson.toJson(batch, gsonBatchType, writer)
        writer.flush()
    }
}
