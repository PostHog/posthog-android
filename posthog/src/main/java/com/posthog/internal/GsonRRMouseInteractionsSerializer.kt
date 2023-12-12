package com.posthog.internal

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type

internal class GsonRRMouseInteractionsSerializer :
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
            null
        }
    }
}
