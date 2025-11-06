package com.posthog.internal

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import java.lang.reflect.Type

internal class GsonPropertyTypeAdapter :
    JsonDeserializer<PropertyType> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): PropertyType? {
        return PropertyType.fromStringOrNull(json.asString)
    }
}
