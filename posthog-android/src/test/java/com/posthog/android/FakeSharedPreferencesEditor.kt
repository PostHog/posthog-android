package com.posthog.android

import android.content.SharedPreferences

internal class FakeSharedPreferencesEditor(private val preferences: MutableMap<String, Any?>) : SharedPreferences.Editor {
    private val pending = mutableMapOf<String, Any?>()
    private var clearRequested = false

    override fun putString(
        key: String,
        value: String?,
    ): SharedPreferences.Editor {
        pending[key] = value
        return this
    }

    override fun putStringSet(
        key: String,
        values: MutableSet<String>?,
    ): SharedPreferences.Editor {
        pending[key] = values?.toMutableSet()
        return this
    }

    override fun putInt(
        key: String,
        value: Int,
    ): SharedPreferences.Editor {
        pending[key] = value
        return this
    }

    override fun putLong(
        key: String,
        value: Long,
    ): SharedPreferences.Editor {
        pending[key] = value
        return this
    }

    override fun putFloat(
        key: String,
        value: Float,
    ): SharedPreferences.Editor {
        pending[key] = value
        return this
    }

    override fun putBoolean(
        key: String,
        value: Boolean,
    ): SharedPreferences.Editor {
        pending[key] = value
        return this
    }

    override fun remove(key: String): SharedPreferences.Editor {
        pending[key] = null
        return this
    }

    override fun clear(): SharedPreferences.Editor {
        clearRequested = true
        return this
    }

    override fun commit(): Boolean {
        apply()
        return true
    }

    override fun apply() {
        if (clearRequested) preferences.clear()
        pending.forEach { (key, value) ->
            if (value == null) preferences.remove(key) else preferences[key] = value
        }
        clearRequested = false
        pending.clear()
    }
}
