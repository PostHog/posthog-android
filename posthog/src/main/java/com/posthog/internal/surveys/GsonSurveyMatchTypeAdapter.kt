package com.posthog.internal.surveys

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.posthog.PostHogConfig
import com.posthog.surveys.SurveyMatchType
import java.lang.reflect.Type

internal class GsonSurveyMatchTypeAdapter(private val config: PostHogConfig) :
    JsonSerializer<SurveyMatchType>,
    JsonDeserializer<SurveyMatchType> {
    override fun serialize(
        src: SurveyMatchType,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement {
        return context.serialize(src.value)
    }

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): SurveyMatchType? {
        return try {
            return SurveyMatchType.fromValue(json.asString)
        } catch (e: Throwable) {
            config.logger.log("${json.asString} isn't a known type: $e.")
            null
        }
    }
}
