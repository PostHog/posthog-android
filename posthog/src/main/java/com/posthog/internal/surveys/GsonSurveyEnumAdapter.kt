package com.posthog.internal.surveys

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.posthog.PostHogConfig
import java.lang.reflect.Type

internal abstract class GsonSurveyEnumAdapter<T>(
    private val config: PostHogConfig,
    private val value: (T) -> String,
    private val fromValue: (String) -> T?,
) : JsonSerializer<T>, JsonDeserializer<T> {
    override fun serialize(
        src: T,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement {
        return context.serialize(value(src))
    }

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): T? {
        return try {
            fromValue(json.asString)
        } catch (e: Throwable) {
            config.logger.log("${json.asString} isn't a known type: $e.")
            null
        }
    }
}
