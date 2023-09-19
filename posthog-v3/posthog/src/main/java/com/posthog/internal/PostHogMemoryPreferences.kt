package com.posthog.internal

import com.posthog.PostHogPreferences

internal class PostHogMemoryPreferences : PostHogPreferences {
    private val preferences = mutableMapOf<String, Any>()

    override fun getValue(key: String, defaultValue: Any?): Any? {
        return preferences[key] ?: defaultValue
    }

    override fun setValue(key: String, value: Any) {
        preferences[key] = value
    }

    override fun clear() {
        preferences.clear()
    }
}
