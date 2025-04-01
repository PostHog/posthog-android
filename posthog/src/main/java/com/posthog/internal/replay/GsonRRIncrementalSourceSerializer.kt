package com.posthog.internal.replay

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.posthog.PostHogConfig
import java.lang.reflect.Type

internal class GsonRRIncrementalSourceSerializer(private val config: PostHogConfig) :
    JsonSerializer<RRIncrementalSource>,
    JsonDeserializer<RRIncrementalSource> {
    override fun serialize(
        src: RRIncrementalSource,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement {
        return context.serialize(src.value)
    }

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): RRIncrementalSource? {
        return try {
            RRIncrementalSource.fromValue(json.asInt)
        } catch (e: Throwable) {
            config.logger.log("${json.asInt} isn't a known type: $e.")
            null
        }
    }
}
