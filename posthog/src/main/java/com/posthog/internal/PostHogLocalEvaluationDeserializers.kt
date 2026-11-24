package com.posthog.internal

import com.google.gson.Gson
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.google.gson.stream.MalformedJsonException
import java.lang.reflect.Type

/**
 * Gson deserializer for PropertyGroup
 */
internal class PropertyGroupDeserializer : JsonDeserializer<PropertyGroup> {
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
internal class PropertyValueDeserializer : JsonDeserializer<PropertyValue> {
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
            PropertyValue.FlagProperties(deserializeFlagProperties(array))
        } else if (firstObject.has("type") && firstObject.has("values")) {
            // Nested PropertyGroups
            val groups =
                array.map { element ->
                    context.deserialize<PropertyGroup>(element, PropertyGroup::class.java)
                }
            PropertyValue.PropertyGroups(groups)
        } else {
            // Otherwise treat as FlagProperties
            PropertyValue.FlagProperties(deserializeFlagProperties(array))
        }
    }

    private fun deserializeFlagProperties(array: com.google.gson.JsonArray): List<FlagProperty> =
        array.mapNotNull { element ->
            if (element.isJsonObject) {
                deserializeFlagProperty(element.asJsonObject)
            } else {
                null
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

        val rawType = jsonObject.get("type")?.asString
        val type = PropertyType.fromStringOrNull(rawType)

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
        ).also { it.rawType = rawType }
    }
}

/**
 * Keeps a flag condition filter's `type` string in [FlagProperty.rawType] when parsing, and writes
 * it back when serializing so a definitions cache round trip does not turn it into `person`.
 */
internal class FlagPropertyTypeAdapterFactory : TypeAdapterFactory {
    override fun <T> create(
        gson: Gson,
        type: TypeToken<T>,
    ): TypeAdapter<T>? {
        if (type.rawType != FlagProperty::class.java) return null
        val delegate = gson.getDelegateAdapter(this, TypeToken.get(FlagProperty::class.java))
        val elementAdapter = gson.getAdapter(JsonElement::class.java)
        val adapter =
            object : TypeAdapter<FlagProperty>() {
                override fun write(
                    out: JsonWriter,
                    value: FlagProperty?,
                ) {
                    val tree = delegate.toJsonTree(value)
                    val rawType = value?.rawType
                    if (rawType != null && tree.isJsonObject) {
                        tree.asJsonObject.addProperty("type", rawType)
                    }
                    elementAdapter.write(out, tree)
                }

                override fun read(input: JsonReader): FlagProperty? {
                    val tree = elementAdapter.read(input)
                    val property = delegate.fromJsonTree(tree) ?: return null
                    val rawType = (tree as? JsonObject)?.get("type")
                    if (rawType is JsonPrimitive && rawType.isString) {
                        property.rawType = rawType.asString
                    }
                    return property
                }
            }
        @Suppress("UNCHECKED_CAST")
        return adapter as TypeAdapter<T>
    }
}

internal class GsonPropertyValueAdapter : JsonDeserializer<PropertyValue>, JsonSerializer<PropertyValue> {
    private val deserializer = PropertyValueDeserializer()

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): PropertyValue? = deserializer.deserialize(json, typeOfT, context)

    override fun serialize(
        src: PropertyValue?,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement? {
        return when (src) {
            is PropertyValue.FlagProperties -> context.serialize(src.values)
            is PropertyValue.PropertyGroups -> context.serialize(src.values)
            null -> null
        }
    }
}
