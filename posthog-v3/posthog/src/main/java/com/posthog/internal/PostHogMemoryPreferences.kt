package com.posthog.internal

import com.posthog.PostHogPreferences

internal class PostHogMemoryPreferences : PostHogPreferences {
    private val lock = Any()
    private val preferences = mutableMapOf<String, Any>()

    override fun getValue(key: String, defaultValue: Any?): Any? {
        synchronized(lock) {
            return preferences[key] ?: defaultValue
        }
    }

    override fun setValue(key: String, value: Any) {
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
}
