package com.posthog.internal

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.reflect.TypeToken
import com.posthog.PostHogConfig
import java.lang.reflect.Type

/**
 * A Gson serializer that safely handles Map<String, Any> by filtering out
 * unserializable values instead of throwing exceptions.
 *
 * Performs depth-first serialization to ensure only unserializable leaf values
 * are dropped, not entire branches containing them.
 *
 * @property config the Config
 */
internal class GsonSafeMapSerializer(private val config: PostHogConfig) : JsonSerializer<Map<String, Any?>> {
    private val mapType: Type = object : TypeToken<Map<String, Any?>>() {}.type

    override fun serialize(
        src: Map<String, Any?>?,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement {
        val jsonObject = JsonObject()

        src?.forEach { (key, srcValue) ->
            val serialized = safeSerializeValue(srcValue, key, context)
            if (serialized != null) {
                jsonObject.add(key, serialized)
            }
        }

        return jsonObject
    }

    /**
     * Recursively serializes a value, handling nested maps and lists to ensure
     * only unserializable leaf values are dropped.
     */
    private fun safeSerializeValue(
        targetValue: Any?,
        key: String,
        context: JsonSerializationContext,
    ): JsonElement? {
        return when (targetValue) {
            null -> null
            is List<*> -> safeSerializeList(targetValue, context)
            is Array<*> -> safeSerializeList(targetValue.toList(), context)
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                serialize(targetValue as Map<String, Any?>, mapType, context)
            }
            else -> {
                try {
                    config.serializer.gson.toJsonTree(targetValue)
                } catch (e: Throwable) {
                    config.logger.log(
                        "Property '$key' with value '$targetValue' cannot be serialized to JSON: $e. " +
                            "This property will be ignored.",
                    )
                    null
                }
            }
        }
    }

    /**
     * Safely serializes a list, filtering out unserializable elements.
     */
    private fun safeSerializeList(
        list: List<*>,
        context: JsonSerializationContext,
    ): JsonElement {
        val jsonArray = JsonArray()

        list.forEach { element ->
            if (element != null) {
                val serialized = safeSerializeValue(element, "list-element", context)
                if (serialized != null) {
                    jsonArray.add(serialized)
                }
            }
        }

        return jsonArray
    }
}
