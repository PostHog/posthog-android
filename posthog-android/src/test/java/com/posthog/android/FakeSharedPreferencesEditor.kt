package com.posthog.android

import android.content.SharedPreferences

internal class FakeSharedPreferencesEditor(private val preferences: MutableMap<String, Any?>) : SharedPreferences.Editor {
    override fun putString(
        key: String,
        value: String?,
    ): SharedPreferences.Editor {
        preferences[key] = value
        return this
    }

    override fun putStringSet(
        key: String,
        values: MutableSet<String>?,
    ): SharedPreferences.Editor {
        preferences[key] = values
        return this
    }

    override fun putInt(
        key: String,
        value: Int,
    ): SharedPreferences.Editor {
        preferences[key] = value
        return this
    }

    override fun putLong(
        key: String,
        value: Long,
    ): SharedPreferences.Editor {
        preferences[key] = value
        return this
    }

    override fun putFloat(
        key: String,
        value: Float,
    ): SharedPreferences.Editor {
        preferences[key] = value
        return this
    }

    override fun putBoolean(
        key: String,
        value: Boolean,
    ): SharedPreferences.Editor {
        preferences[key] = value
        return this
    }

    override fun remove(key: String): SharedPreferences.Editor {
        preferences.remove(key)
        return this
    }

    override fun clear(): SharedPreferences.Editor {
        preferences.clear()
        return this
    }

    override fun commit(): Boolean {
        return true
    }

    override fun apply() {
    }
}
