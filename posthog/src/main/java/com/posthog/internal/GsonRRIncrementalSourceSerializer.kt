package com.posthog.internal

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type

public class GsonRRIncrementalSourceSerializer :
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
    ): RRIncrementalSource {
        return RRIncrementalSource.fromValue(json.asInt)
    }
}
