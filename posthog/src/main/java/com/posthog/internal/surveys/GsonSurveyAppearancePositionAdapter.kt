package com.posthog.internal.surveys

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.posthog.PostHogConfig
import com.posthog.surveys.SurveyAppearancePosition
import java.lang.reflect.Type

internal class GsonSurveyAppearancePositionAdapter(private val config: PostHogConfig) :
    JsonSerializer<SurveyAppearancePosition>,
    JsonDeserializer<SurveyAppearancePosition> {
    override fun serialize(
        src: SurveyAppearancePosition,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement {
        return context.serialize(src.value)
    }

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): SurveyAppearancePosition? {
        return try {
            return SurveyAppearancePosition.fromValue(json.asString)
        } catch (e: Throwable) {
            config.logger.log("${json.asString} isn't a known type: $e.")
            null
        }
    }
}
