package com.posthog.android.internal

import android.content.Context
import android.content.Context.MODE_PRIVATE
import com.posthog.PostHogPreferences
import com.posthog.android.PostHogAndroidConfig

internal class PostHogSharedPreferences(context: Context, config: PostHogAndroidConfig) :
    PostHogPreferences {

    private val sharedPreferences = context.getSharedPreferences("posthog-android-${config.apiKey}", MODE_PRIVATE)

    private val lock = Any()

    override fun getValue(key: String, defaultValue: Any?): Any? {
        val defValue: Any?
        synchronized(lock) {
            defValue = sharedPreferences.all[key] ?: defaultValue
        }
        return defValue
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
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    (value as? Set<String>)?.let {
                        edit.putStringSet(key, it)
                    }
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

    override fun remove(key: String) {
        val edit = sharedPreferences.edit()
        synchronized(lock) {
            edit.remove(key)
            edit.apply()
        }
    }

    override fun getAll(): Map<String, Any> {
        val props: Map<String, Any>
        synchronized(lock) {
            @Suppress("UNCHECKED_CAST")
            props = sharedPreferences.all.toMap() as? Map<String, Any> ?: emptyMap()
        }
        return props
    }
}
