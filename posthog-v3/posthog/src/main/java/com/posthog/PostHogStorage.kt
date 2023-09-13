package com.posthog

internal class PostHogStorage(private val config: PostHogConfig) {
    // TODO: move to disk cache instead of memory cache
    private val keyValues = mutableMapOf<String, Any>()
    private val lock = Any()

    fun getString(key: String, defaultValue: String? = null): String? {
        var value: String?
        synchronized(lock) {
            val tempValue = keyValues[key]
            value = if (tempValue != null && tempValue is String) tempValue else tempValue?.toString()
        }
        return value ?: defaultValue
    }

    fun setString(key: String, value: String) {
        synchronized(lock) {
            keyValues[key] = value
        }
    }
}
