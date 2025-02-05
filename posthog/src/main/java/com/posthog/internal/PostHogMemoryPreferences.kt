package com.posthog.internal

import com.posthog.PostHogVisibleForTesting

/**
 * The in memory preferences implementation
 */
@PostHogVisibleForTesting
public class PostHogMemoryPreferences : PostHogPreferences {
    private val lock = Any()
    private val preferences = mutableMapOf<String, Any>()

    override fun getValue(
        key: String,
        defaultValue: Any?,
    ): Any? {
        val value: Any?
        synchronized(lock) {
            value = preferences[key] ?: defaultValue
        }
        return value
    }

    override fun setValue(
        key: String,
        value: Any,
    ) {
        synchronized(lock) {
            preferences[key] = value
        }
    }

    override fun clear(except: List<String>) {
        synchronized(lock) {
            val it = preferences.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                if (!except.contains(entry.key)) {
                    it.remove()
                }
            }
        }
    }

    override fun remove(key: String) {
        synchronized(lock) {
            preferences.remove(key)
        }
    }

    override fun getAll(): Map<String, Any> {
        val props: Map<String, Any>
        synchronized(lock) {
            props = preferences.toMap()
        }
        return props.filterKeys { key ->
            !PostHogPreferences.ALL_INTERNAL_KEYS.contains(key)
        }
    }
}
