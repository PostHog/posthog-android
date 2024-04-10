package com.posthog.android

import android.content.SharedPreferences

internal class FakeSharedPreferences : SharedPreferences {
    private val preferences = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> {
        return preferences
    }

    override fun getString(
        key: String,
        defValue: String?,
    ): String? {
        return preferences[key] as? String ?: defValue
    }

    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST")
        return preferences[key] as? MutableSet<String> ?: defValues
    }

    override fun getInt(
        key: String,
        defValue: Int,
    ): Int {
        return preferences[key] as? Int ?: defValue
    }

    override fun getLong(
        key: String,
        defValue: Long,
    ): Long {
        return preferences[key] as? Long ?: defValue
    }

    override fun getFloat(
        key: String,
        defValue: Float,
    ): Float {
        return preferences[key] as? Float ?: defValue
    }

    override fun getBoolean(
        key: String,
        defValue: Boolean,
    ): Boolean {
        return preferences[key] as? Boolean ?: defValue
    }

    override fun contains(key: String): Boolean {
        return preferences.contains(key)
    }

    override fun edit(): SharedPreferences.Editor {
        return FakeSharedPreferencesEditor(preferences)
    }

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
    }
}
