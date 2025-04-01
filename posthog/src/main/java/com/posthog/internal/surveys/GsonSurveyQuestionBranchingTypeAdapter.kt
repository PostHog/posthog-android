package com.posthog.internal.surveys

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.posthog.PostHogConfig
import com.posthog.surveys.SurveyQuestionBranchingType
import java.lang.reflect.Type

internal class GsonSurveyQuestionBranchingTypeAdapter(private val config: PostHogConfig) :
    JsonSerializer<SurveyQuestionBranchingType>,
    JsonDeserializer<SurveyQuestionBranchingType> {
    override fun serialize(
        src: SurveyQuestionBranchingType,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement {
        return context.serialize(src.value)
    }

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): SurveyQuestionBranchingType? {
        return try {
            return SurveyQuestionBranchingType.fromValue(json.asString)
        } catch (e: Throwable) {
            config.logger.log("${json.asString} isn't a known type: $e.")
            null
        }
    }
}
