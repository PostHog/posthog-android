package com.posthog.internal.replay

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.posthog.PostHogConfig
import java.lang.reflect.Type

internal class GsonRRMouseInteractionsSerializer(private val config: PostHogConfig) :
    JsonSerializer<RRMouseInteraction>,
    JsonDeserializer<RRMouseInteraction> {
    override fun serialize(
        src: RRMouseInteraction,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement {
        return context.serialize(src.value)
    }

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): RRMouseInteraction? {
        return try {
            RRMouseInteraction.fromValue(json.asInt)
        } catch (e: Throwable) {
            config.logger.log("${json.asInt} isn't a known type: $e.")
            null
        }
    }
}
