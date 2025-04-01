package com.posthog.internal.surveys

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.posthog.PostHogConfig
import com.posthog.surveys.SurveyTextContentType
import java.lang.reflect.Type

internal class GsonSurveyTextContentTypeAdapter(private val config: PostHogConfig) :
    JsonSerializer<SurveyTextContentType>,
    JsonDeserializer<SurveyTextContentType> {
    override fun serialize(
        src: SurveyTextContentType,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement {
        return context.serialize(src.value)
    }

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): SurveyTextContentType? {
        return try {
            SurveyTextContentType.fromValue(json.asString)
        } catch (e: Throwable) {
            config.logger.log("${json.asInt} isn't a known type: $e.")
            null
        }
    }
}
