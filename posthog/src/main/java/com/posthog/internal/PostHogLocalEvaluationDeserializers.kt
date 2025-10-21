package com.posthog.internal

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.stream.MalformedJsonException
import com.posthog.PostHogInternal
import java.lang.reflect.Type

/**
 * Gson deserializer for PropertyGroup
 */
@PostHogInternal
public class PropertyGroupDeserializer : JsonDeserializer<PropertyGroup> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): PropertyGroup {
        val jsonObject = json.asJsonObject

        // Parse type (AND/OR)
        val type =
            when (jsonObject.get("type")?.asString) {
                "AND" -> LogicalOperator.AND
                "OR" -> LogicalOperator.OR
                else -> null
            }

        // Parse values
        val values =
            jsonObject.get("values")?.let { valuesElement ->
                context.deserialize<PropertyValue>(valuesElement, PropertyValue::class.java)
            }

        return PropertyGroup(type, values)
    }
}

/**
 * Gson deserializer for PropertyValue sealed interface
 * Determines whether to deserialize as PropertyGroups or FlagProperties based on structure
 */
@PostHogInternal
public class PropertyValueDeserializer : JsonDeserializer<PropertyValue> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): PropertyValue? {
        if (!json.isJsonArray) return null

        val array = json.asJsonArray
        if (array.size() == 0) return null

        // Check first element to determine type
        val first = array.get(0)
        if (!first.isJsonObject) return null

        val firstObject = first.asJsonObject

        // Distinguish between PropertyGroup and FlagProperty:
        // If first element has "key" field, it's FlagProperties
        // If first element has both "type" AND "values" fields (but no "key"), it's nested PropertyGroups
        return if (firstObject.has("key")) {
            // FlagProperties
            val properties =
                array.mapNotNull { element ->
                    if (element.isJsonObject) {
                        deserializeFlagProperty(element.asJsonObject)
                    } else {
                        null
                    }
                }
            PropertyValue.FlagProperties(properties)
        } else if (firstObject.has("type") && firstObject.has("values")) {
            // Nested PropertyGroups
            val groups =
                array.map { element ->
                    context.deserialize<PropertyGroup>(element, PropertyGroup::class.java)
                }
            PropertyValue.PropertyGroups(groups)
        } else {
            // Otherwise treat as FlagProperties
            val properties =
                array.mapNotNull { element ->
                    if (element.isJsonObject) {
                        deserializeFlagProperty(element.asJsonObject)
                    } else {
                        null
                    }
                }
            PropertyValue.FlagProperties(properties)
        }
    }

    private fun deserializeFlagProperty(jsonObject: com.google.gson.JsonObject): FlagProperty? {
        val key = jsonObject.get("key")?.asString ?: return null
        val value =
            jsonObject.get("value")?.let { element ->
                when {
                    element.isJsonPrimitive -> {
                        val primitive = element.asJsonPrimitive
                        when {
                            primitive.isBoolean -> primitive.asBoolean
                            primitive.isNumber -> {
                                // Use same logic as GsonNumberPolicy
                                // Try Int, then Long, then Double
                                val numStr = primitive.asString
                                try {
                                    numStr.toInt()
                                } catch (intE: NumberFormatException) {
                                    try {
                                        numStr.toLong()
                                    } catch (longE: NumberFormatException) {
                                        val d = numStr.toDouble()
                                        if ((d.isInfinite() || d.isNaN())) {
                                            throw MalformedJsonException("failed to parse number: " + d)
                                        }
                                        d
                                    }
                                }
                            }
                            primitive.isString -> primitive.asString
                            else -> null
                        }
                    }
                    element.isJsonArray -> {
                        element.asJsonArray.map { it.asString }
                    }
                    else -> null
                }
            }

        val operator =
            jsonObject.get("operator")?.asString?.let {
                PropertyOperator.fromStringOrNull(it)
            }

        val type =
            jsonObject.get("type")?.asString?.let {
                PropertyType.fromStringOrNull(it)
            }

        val negation = jsonObject.get("negation")?.asBoolean

        val dependencyChain =
            jsonObject.get("dependency_chain")?.asJsonArray?.mapNotNull {
                if (it.isJsonPrimitive && it.asJsonPrimitive.isString) {
                    it.asString
                } else {
                    null
                }
            }

        return FlagProperty(
            key = key,
            propertyValue = value,
            propertyOperator = operator,
            type = type,
            negation = negation,
            dependencyChain = dependencyChain,
        )
    }
}
