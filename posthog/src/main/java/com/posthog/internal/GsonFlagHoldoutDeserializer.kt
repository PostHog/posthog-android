package com.posthog.internal

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.lang.reflect.Type

/**
 * Reads a flag's `holdout` leniently: anything but an object with a numeric `exclusion_percentage`
 * is no holdout, so a malformed holdout doesn't fail the whole definitions response.
 */
internal class GsonFlagHoldoutDeserializer : JsonDeserializer<FlagHoldout> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): FlagHoldout? {
        val percentage = (json as? JsonObject)?.get("exclusion_percentage")
        if (percentage == null || !percentage.isJsonPrimitive || !percentage.asJsonPrimitive.isNumber) {
            return null
        }
        return FlagHoldout(percentage.asDouble)
    }
}
