package com.posthog.android.internal

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import com.posthog.android.PostHogAndroidConfig
import com.posthog.internal.PostHogPreferences
import com.posthog.internal.PostHogPreferences.Companion.ALL_INTERNAL_KEYS
import com.posthog.internal.PostHogPreferences.Companion.GROUPS
import com.posthog.internal.PostHogPreferences.Companion.STRINGIFIED_KEYS

/**
 * Reads and writes to the SDKs shared preferences
 * The shared pref is called "posthog-android-$apiKey"
 * @property context the App Context
 * @property config the Config
 * @property sharedPreferences The SharedPreferences, defaults to context.getSharedPreferences(...)
 */
internal class PostHogSharedPreferences(
    private val context: Context,
    private val config: PostHogAndroidConfig,
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("posthog-android-${config.apiKey}", MODE_PRIVATE),
) :
    PostHogPreferences {
    private val lock = Any()

    override fun getValue(
        key: String,
        defaultValue: Any?,
    ): Any? {
        val value: Any?
        synchronized(lock) {
            value = sharedPreferences.all[key] ?: defaultValue
        }

        val stringifiedKeys = getStringifiedKeys()
        return convertValue(key, value, stringifiedKeys)
    }

    private fun convertValue(
        key: String,
        value: Any?,
        keys: Set<String>,
    ): Any? {
        return when (value) {
            is String -> {
                // we only want to deserialize special keys
                // or keys that were stringified.
                if (SPECIAL_KEYS.contains(key) ||
                    keys.contains(key)
                ) {
                    deserializeObject(value)
                } else {
                    value
                }
            }
            else -> {
                value
            }
        }
    }

    override fun setValue(
        key: String,
        value: Any,
    ) {
        val edit = sharedPreferences.edit()

        synchronized(lock) {
            when (value) {
                is Boolean -> {
                    edit.putBoolean(key, value)
                }

                is String -> {
                    edit.putString(key, value)
                }

                is Float -> {
                    edit.putFloat(key, value)
                }

                is Long -> {
                    edit.putLong(key, value)
                }

                is Int -> {
                    edit.putInt(key, value)
                }
                is Collection<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    (value.toSet() as? Set<String>)?.let {
                        edit.putStringSet(key, it)
                    } ?: run {
                        serializeObject(key, value, edit)
                    }
                }
                is Array<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    (value.toSet() as? Set<String>)?.let {
                        edit.putStringSet(key, it)
                    } ?: run {
                        serializeObject(key, value, edit)
                    }
                } else -> {
                    serializeObject(key, value, edit)
                }
            }

            edit.apply()
        }
    }

    override fun clear(except: List<String>) {
        val edit = sharedPreferences.edit()

        synchronized(lock) {
            val it = sharedPreferences.all.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                if (!except.contains(entry.key)) {
                    edit.remove(entry.key)
                }
            }

            edit.apply()
        }
    }

    private fun addToStringifiedKeys(
        key: String,
        editor: SharedPreferences.Editor,
    ) {
        val stringifiedKeys = getStringifiedKeys() + key
        editor.putStringSet(STRINGIFIED_KEYS, stringifiedKeys)
    }

    private fun removeFromStringifiedKeys(
        key: String,
        editor: SharedPreferences.Editor,
    ) {
        val keys = getStringifiedKeys().toMutableSet()
        if (!keys.contains(key)) {
            return
        }
        keys.remove(key)
        editor.putStringSet(STRINGIFIED_KEYS, keys)
    }

    private fun getStringifiedKeys(): Set<String> {
        return sharedPreferences.getStringSet(STRINGIFIED_KEYS, setOf()) ?: setOf()
    }

    private fun serializeObject(
        key: String,
        value: Any,
        editor: SharedPreferences.Editor,
    ) {
        try {
            config.serializer.serializeObject(value)?.let {
                editor.putString(key, it)

                addToStringifiedKeys(key, editor)
            } ?: run {
                config.logger.log("Value type: ${value.javaClass.name} and value: $value isn't valid.")
            }
        } catch (e: Throwable) {
            config.logger.log("Value type: ${value.javaClass.name} and value: $value isn't valid.")
        }
    }

    private fun deserializeObject(value: String): Any {
        try {
            config.serializer.deserializeString(value)?.let {
                // only return the deserialized object if it's not null otherwise fallback
                // to the original (and stringified) value
                return it
            }
        } catch (ignored: Throwable) {
        }
        return value
    }

    override fun remove(key: String) {
        val edit = sharedPreferences.edit()
        synchronized(lock) {
            edit.remove(key)
            removeFromStringifiedKeys(key, edit)
            edit.apply()
        }
    }

    override fun getAll(): Map<String, Any> {
        val allPreferences: Map<String, Any>
        synchronized(lock) {
            @Suppress("UNCHECKED_CAST")
            allPreferences = sharedPreferences.all.toMap() as? Map<String, Any> ?: emptyMap()
        }
        val filteredPreferences =
            allPreferences.filterKeys { key ->
                !ALL_INTERNAL_KEYS.contains(key)
            }
        val preferences = mutableMapOf<String, Any>()
        val stringifiedKeys = getStringifiedKeys()
        for ((key, value) in filteredPreferences) {
            convertValue(key, value, stringifiedKeys)?.let {
                preferences[key] = it
            }
        }

        return preferences
    }

    private companion object {
        private val SPECIAL_KEYS = listOf(GROUPS)
    }
}
