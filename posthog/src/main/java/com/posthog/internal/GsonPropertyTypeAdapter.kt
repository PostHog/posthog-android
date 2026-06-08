package com.posthog.internal

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type

internal class GsonPropertyTypeAdapter :
    JsonDeserializer<PropertyType>,
    JsonSerializer<PropertyType> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): PropertyType? {
        return PropertyType.fromStringOrNull(json.asString)
    }

    override fun serialize(
        src: PropertyType?,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement? {
        return src?.let { JsonPrimitive(it.toApiString()) }
    }

    private fun PropertyType.toApiString(): String =
        when (this) {
            PropertyType.COHORT -> "cohort"
            PropertyType.FLAG -> "flag"
            PropertyType.PERSON -> "person"
        }
}
