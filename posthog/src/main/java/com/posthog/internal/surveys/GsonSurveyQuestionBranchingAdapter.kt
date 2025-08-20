package com.posthog.internal.surveys

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.posthog.PostHogConfig
import com.posthog.surveys.SurveyQuestionBranching
import java.lang.reflect.Type

internal class GsonSurveyQuestionBranchingAdapter(private val config: PostHogConfig) :
    JsonSerializer<SurveyQuestionBranching>,
    JsonDeserializer<SurveyQuestionBranching> {
    override fun serialize(
        src: SurveyQuestionBranching,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement {
        return context.serialize(src, typeOfSrc)
    }

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): SurveyQuestionBranching? {
        return try {
            val jsonObject = json.asJsonObject
            val type = jsonObject.get("type")?.asString

            return when (type) {
                "next_question" -> SurveyQuestionBranching.Next
                "end" -> SurveyQuestionBranching.End
                "response_based" -> {
                    val responseValues = jsonObject.get("responseValues")?.asJsonObject
                    val responseMap =
                        responseValues?.entrySet()?.associate { entry ->
                            entry.key to
                                when {
                                    entry.value.isJsonPrimitive && entry.value.asJsonPrimitive.isNumber -> {
                                        entry.value.asInt
                                    }
                                    entry.value.isJsonPrimitive && entry.value.asJsonPrimitive.isString -> {
                                        entry.value.asString
                                    }
                                    else -> entry.value.toString()
                                }
                        } ?: emptyMap()
                    SurveyQuestionBranching.ResponseBased(responseMap)
                }
                "specific_question" -> {
                    val index = jsonObject.get("index")?.asInt ?: 0
                    SurveyQuestionBranching.SpecificQuestion(index)
                }
                else -> {
                    config.logger.log("Unknown branching type: $type")
                    null
                }
            }
        } catch (e: Throwable) {
            config.logger.log("$json isn't a valid SurveyQuestionBranching: $e.")
            null
        }
    }
}
