package com.posthog

import com.google.gson.Gson

/**
 * The result of evaluating a feature flag.
 *
 * @property key the key of the feature flag
 * @property enabled whether the feature flag is enabled
 * @property variant the variant of the feature flag (null for boolean flags)
 * @property payload the payload associated with the feature flag (if any)
 */
public class FeatureFlagResult(
    public val key: String,
    public val enabled: Boolean,
    public val variant: String?,
    public val payload: Any?,
) {
    /**
     * Returns the effective value of the feature flag.
     * For multivariate flags, returns the variant string.
     * For boolean flags, returns the enabled boolean.
     */
    public val value: Any
        get() = variant ?: enabled

    internal companion object {
        internal val gson: Gson by lazy { Gson() }
    }

    /**
     * Returns the payload serialized as a JSON string.
     *
     * If the payload is already a string, returns it as-is.
     * If the payload is a primitive (Number, Boolean), returns its string representation.
     * If the payload is an object (Map, List, etc.), serializes it to JSON.
     * Returns null if the payload is null.
     */
    public fun serializedPayload(): String? {
        if (payload == null) return null
        return try {
            when (payload) {
                is String -> payload
                is Number, is Boolean -> payload.toString()
                else -> gson.toJson(payload)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Returns the payload deserialized as the specified type.
     *
     * If the payload is already an instance of T, returns it directly.
     * Otherwise, attempts to deserialize from JSON.
     *
     * @return the payload as type T, or null if the payload is null or deserialization fails
     */
    public inline fun <reified T> getPayloadAs(): T? = getPayloadAs(T::class.java)

    /**
     * Returns the payload deserialized as the specified class.
     *
     * If the payload is already an instance of the specified class, returns it directly.
     * Otherwise, attempts to deserialize from JSON.
     *
     * @param clazz the class to deserialize the payload to
     * @return the payload as type T, or null if the payload is null or deserialization fails
     */
    @Suppress("UNCHECKED_CAST")
    public fun <T> getPayloadAs(clazz: Class<T>): T? {
        if (payload == null) return null
        if (clazz.isInstance(payload)) return payload as T
        return try {
            gson.fromJson(serializedPayload(), clazz)
        } catch (e: Exception) {
            null
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FeatureFlagResult) return false
        return key == other.key &&
            enabled == other.enabled &&
            variant == other.variant &&
            payload == other.payload
    }

    override fun hashCode(): Int {
        var result = key.hashCode()
        // Use Java 7 compatible approach (1231/1237 are the values used by Boolean.hashCode)
        result = 31 * result + (if (enabled) 1231 else 1237)
        result = 31 * result + (variant?.hashCode() ?: 0)
        result = 31 * result + (payload?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "FeatureFlagResult(key='$key', enabled=$enabled, variant=$variant, payload=$payload)"
    }
}
