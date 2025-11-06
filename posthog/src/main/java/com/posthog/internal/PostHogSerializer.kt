package com.posthog.internal

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonIOException
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.posthog.PostHogConfig
import com.posthog.PostHogInternal
import com.posthog.internal.replay.GsonRREventTypeSerializer
import com.posthog.internal.replay.GsonRRIncrementalSourceSerializer
import com.posthog.internal.replay.GsonRRMouseInteractionsSerializer
import com.posthog.internal.replay.RREventType
import com.posthog.internal.replay.RRIncrementalSource
import com.posthog.internal.replay.RRMouseInteraction
import com.posthog.internal.surveys.GsonSurveyAppearancePositionAdapter
import com.posthog.internal.surveys.GsonSurveyAppearanceWidgetTypeAdapter
import com.posthog.internal.surveys.GsonSurveyMatchTypeAdapter
import com.posthog.internal.surveys.GsonSurveyQuestionAdapter
import com.posthog.internal.surveys.GsonSurveyQuestionBranchingAdapter
import com.posthog.internal.surveys.GsonSurveyQuestionTypeAdapter
import com.posthog.internal.surveys.GsonSurveyRatingDisplayTypeAdapter
import com.posthog.internal.surveys.GsonSurveyTextContentTypeAdapter
import com.posthog.internal.surveys.GsonSurveyTypeAdapter
import com.posthog.surveys.SurveyAppearancePosition
import com.posthog.surveys.SurveyAppearanceWidgetType
import com.posthog.surveys.SurveyMatchType
import com.posthog.surveys.SurveyQuestion
import com.posthog.surveys.SurveyQuestionBranching
import com.posthog.surveys.SurveyQuestionType
import com.posthog.surveys.SurveyRatingDisplayType
import com.posthog.surveys.SurveyTextContentType
import com.posthog.surveys.SurveyType
import java.io.IOException
import java.io.Reader
import java.io.Writer
import java.util.Date

/**
 * The JSON serializer using Gson
 * @property config the Config
 */
@PostHogInternal
public class PostHogSerializer(private val config: PostHogConfig) {
    public val gson: Gson =
        GsonBuilder().apply {
            // general
            setObjectToNumberStrategy(GsonNumberPolicy())
            registerTypeAdapter(Date::class.java, GsonDateTypeAdapter(config))
            val mapSerializer = GsonSafeMapSerializer(config)
            registerTypeAdapter(
                object : TypeToken<Map<String, Any?>>() {}.type,
                mapSerializer,
            )
            registerTypeAdapter(
                object : TypeToken<MutableMap<String, Any?>>() {}.type,
                mapSerializer,
            )
                .setLenient()
            // replay
            registerTypeAdapter(RREventType::class.java, GsonRREventTypeSerializer(config))
            registerTypeAdapter(RRIncrementalSource::class.java, GsonRRIncrementalSourceSerializer(config))
            registerTypeAdapter(RRMouseInteraction::class.java, GsonRRMouseInteractionsSerializer(config))
            // surveys
            registerTypeAdapter(SurveyAppearancePosition::class.java, GsonSurveyAppearancePositionAdapter(config))
            registerTypeAdapter(SurveyAppearanceWidgetType::class.java, GsonSurveyAppearanceWidgetTypeAdapter(config))
            registerTypeAdapter(SurveyMatchType::class.java, GsonSurveyMatchTypeAdapter(config))

            registerTypeAdapter(SurveyQuestionType::class.java, GsonSurveyQuestionTypeAdapter(config))
            registerTypeAdapter(SurveyRatingDisplayType::class.java, GsonSurveyRatingDisplayTypeAdapter(config))
            registerTypeAdapter(SurveyTextContentType::class.java, GsonSurveyTextContentTypeAdapter(config))
            registerTypeAdapter(SurveyType::class.java, GsonSurveyTypeAdapter(config))
            registerTypeAdapter(SurveyQuestion::class.java, GsonSurveyQuestionAdapter(config))
            registerTypeAdapter(SurveyQuestionBranching::class.java, GsonSurveyQuestionBranchingAdapter(config))
            // local evaluation
            registerTypeAdapter(PropertyGroup::class.java, PropertyGroupDeserializer())
            registerTypeAdapter(PropertyValue::class.java, PropertyValueDeserializer())
            registerTypeAdapter(PropertyOperator::class.java, GsonPropertyOperatorAdapter())
            registerTypeAdapter(PropertyType::class.java, GsonPropertyTypeAdapter())
        }.create()

    @Throws(JsonIOException::class, IOException::class)
    public inline fun <reified T> serialize(
        value: T,
        writer: Writer,
    ) {
        gson.toJson(value, object : TypeToken<T>() {}.type, writer)
        writer.flush()
    }

    @Throws(JsonIOException::class, JsonSyntaxException::class)
    public inline fun <reified T> deserialize(reader: Reader): T {
        return gson.fromJson(reader, object : TypeToken<T>() {}.type)
    }

    @Throws(JsonIOException::class, JsonSyntaxException::class)
    public inline fun <reified T> deserializeList(list: List<*>): List<T>? {
        val jsonElement = gson.toJsonTree(list)
        return gson.fromJson(jsonElement, object : TypeToken<List<T>>() {}.type)
    }

    public fun deserializeString(json: String): Any? {
        return gson.fromJson(json, Any::class.java)
    }

    public fun serializeObject(value: Any): String? {
        return gson.toJson(value, Any::class.java)
    }
}
