package com.posthog.internal.surveys

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.posthog.PostHogConfig
import com.posthog.surveys.SurveyQuestionType
import java.lang.reflect.Type

internal class GsonSurveyQuestionTypeAdapter(private val config: PostHogConfig) :
    JsonSerializer<SurveyQuestionType>,
    JsonDeserializer<SurveyQuestionType> {
    override fun serialize(
        src: SurveyQuestionType,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement {
        return context.serialize(src.value)
    }

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): SurveyQuestionType? {
        return try {
            return SurveyQuestionType.fromValue(json.asString)
        } catch (e: Throwable) {
            config.logger.log("${json.asString} isn't a known type: $e.")
            null
        }
    }
}
