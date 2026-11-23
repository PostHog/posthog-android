package com.posthog.internal

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
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
 * are dropped, not entire branches containing them. Null-valued map members are
 * omitted; list elements keep their indices (see [safeSerializeList]).
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

        // A nested map can have any key type at runtime, whatever this signature says.
        (src as Map<*, *>?)?.forEach { (rawKey, srcValue) ->
            val key =
                jsonKey(rawKey) ?: run {
                    // the key's type only: keys can hold user content
                    config.logger.log(
                        "Property key of type ${rawKey?.javaClass?.name} cannot be serialized to JSON. " +
                            "This property will be ignored.",
                    )
                    return@forEach
                }
            val serialized = safeSerializeValue(srcValue, key, context)
            if (serialized != null) {
                jsonObject.add(key, serialized)
            }
        }

        return jsonObject
    }

    /** The JSON name for [key]: scalars are converted, and any other key has none. */
    private fun jsonKey(key: Any?): String? {
        return when (key) {
            null -> "null"
            is String -> key
            is Number, is Boolean -> key.toString()
            else -> null
        }
    }

    /**
     * Recursively serializes a value, handling nested maps and lists to ensure
     * only unserializable leaf values are dropped.
     */
    private fun safeSerializeValue(
        targetValue: Any?,
        key: String,
        context: JsonSerializationContext,
        inList: Boolean = false,
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
                    // the value's type only: properties can hold prompts and other user content
                    config.logger.log(
                        "Property '$key' of type ${targetValue.javaClass.name} cannot be serialized to JSON " +
                            "(${e.javaClass.simpleName}). " +
                            (if (inList) "It will be sent as null." else "This property will be ignored."),
                    )
                    null
                }
            }
        }
    }

    /**
     * Safely serializes a list, keeping every element at its index: a null element is written as
     * JSON `null`, and so is an element that can't be serialized, so the array is never compacted.
     */
    private fun safeSerializeList(
        list: List<*>,
        context: JsonSerializationContext,
    ): JsonElement {
        val jsonArray = JsonArray()

        list.forEach { element ->
            jsonArray.add(safeSerializeValue(element, "list-element", context, inList = true) ?: JsonNull.INSTANCE)
        }

        return jsonArray
    }
}
