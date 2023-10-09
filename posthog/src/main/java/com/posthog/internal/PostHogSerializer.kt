package com.posthog.internal

import com.google.gson.GsonBuilder
import com.google.gson.JsonIOException
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.posthog.PostHogConfig
import java.io.IOException
import java.io.Reader
import java.io.Writer
import java.util.Date

/**
 * The JSON serializer using Gson
 * @property config the Config
 */
internal class PostHogSerializer(private val config: PostHogConfig) {
    private val gson = GsonBuilder().apply {
        setObjectToNumberStrategy(GsonNumberPolicy())
        registerTypeAdapter(Date::class.java, GsonDateTypeAdapter(config))
            .setLenient()
    }.create()

    @Throws(JsonIOException::class, IOException::class)
    inline fun <reified T> serialize(value: T, writer: Writer) {
        gson.toJson(value, object : TypeToken<T>() {}.type, writer)
        writer.flush()
    }

    @Throws(JsonIOException::class, JsonSyntaxException::class)
    inline fun <reified T> deserialize(reader: Reader): T {
        return gson.fromJson(reader, object : TypeToken<T>() {}.type)
    }

    fun deserializeString(json: String): Any? {
        return gson.fromJson(json, Any::class.java)
    }
}
