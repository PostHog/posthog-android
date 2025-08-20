package com.posthog.internal.surveys

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.posthog.PostHogConfig
import com.posthog.surveys.LinkSurveyQuestion
import com.posthog.surveys.MultipleSurveyQuestion
import com.posthog.surveys.OpenSurveyQuestion
import com.posthog.surveys.RatingSurveyQuestion
import com.posthog.surveys.SingleSurveyQuestion
import com.posthog.surveys.SurveyQuestion
import java.lang.reflect.Type

internal class GsonSurveyQuestionAdapter(private val config: PostHogConfig) :
    JsonSerializer<SurveyQuestion>,
    JsonDeserializer<SurveyQuestion> {
    override fun serialize(
        src: SurveyQuestion,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement {
        return context.serialize(src, typeOfSrc)
    }

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): SurveyQuestion? {
        return try {
            val type = json.asJsonObject.get("type").asString

            return when (type) {
                "open" -> context.deserialize(json, OpenSurveyQuestion::class.java)
                "link" -> context.deserialize(json, LinkSurveyQuestion::class.java)
                "rating" -> context.deserialize(json, RatingSurveyQuestion::class.java)
                "multiple_choice" -> context.deserialize(json, MultipleSurveyQuestion::class.java)
                "single_choice" -> context.deserialize(json, SingleSurveyQuestion::class.java)
                else -> null
            }
        } catch (e: Throwable) {
            config.logger.log("${json.asString} isn't a known type: $e.")
            null
        }
    }
}
