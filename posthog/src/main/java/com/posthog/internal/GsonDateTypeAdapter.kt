package com.posthog.internal

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.posthog.PostHogConfig
import java.lang.reflect.Type
import java.util.Date

/**
 * a Gson Type adapter to serialize and deserialize the Java Date type
 * @property config the Config
 */
internal class GsonDateTypeAdapter(private val config: PostHogConfig) : JsonDeserializer<Date>, JsonSerializer<Date> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): Date? {
        return try {
            parseISO8601Date(json.asString)
        } catch (e: Throwable) {
            config.logger.log("${json.asString} isn't a deserializable ISO8601 Date: $e.")
            null
        }
    }

    override fun serialize(
        src: Date,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement? {
        return try {
            val dateStr = formatISO8601Date(src)
            JsonPrimitive(dateStr)
        } catch (e: Throwable) {
            config.logger.log("$src isn't a serializable ISO8601 Date: $e.")
            null
        }
    }
}
