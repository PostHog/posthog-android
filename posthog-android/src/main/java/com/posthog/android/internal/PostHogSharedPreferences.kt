package com.posthog.android.internal

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import com.posthog.android.PostHogAndroidConfig
import com.posthog.internal.PostHogPreferences
import com.posthog.internal.PostHogPreferences.Companion.GROUPS

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

    override fun getValue(key: String, defaultValue: Any?): Any? {
        val value: Any?
        synchronized(lock) {
            value = sharedPreferences.all[key] ?: defaultValue
        }

        return when (value) {
            is String -> {
                // we only want to deserialize special keys
                if (SPECIAL_KEYS.contains(key)) {
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

    override fun setValue(key: String, value: Any) {
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
                        stringifyObject(key, value, edit)
                    }
                }
                is Array<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    (value.toSet() as? Set<String>)?.let {
                        edit.putStringSet(key, it)
                    } ?: run {
                        stringifyObject(key, value, edit)
                    }
                } else -> {
                    stringifyObject(key, value, edit)
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

    private fun stringifyObject(key: String, value: Any, editor: SharedPreferences.Editor) {
        try {
            config.serializer.serializeObject(value)?.let {
                editor.putString(key, it)
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
        } catch (ignored: Throwable) { }
        return value
    }

    override fun remove(key: String) {
        val edit = sharedPreferences.edit()
        synchronized(lock) {
            edit.remove(key)
            edit.apply()
        }
    }

    override fun getAll(): Map<String, Any> {
        val preferences: Map<String, Any>
        synchronized(lock) {
            @Suppress("UNCHECKED_CAST")
            preferences = sharedPreferences.all.toMap() as? Map<String, Any> ?: emptyMap()
        }
        return preferences
    }

    companion object {
        private val SPECIAL_KEYS = listOf(GROUPS)
    }
}
