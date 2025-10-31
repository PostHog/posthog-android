package com.posthog.internal

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import java.lang.reflect.Type

internal class GsonPropertyOperatorAdapter :
    JsonDeserializer<PropertyOperator> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): PropertyOperator? {
        return PropertyOperator.fromStringOrNull(json.asString)
    }
}
