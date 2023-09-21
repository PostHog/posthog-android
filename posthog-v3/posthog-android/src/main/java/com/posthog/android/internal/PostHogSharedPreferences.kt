package com.posthog.android.internal

import android.content.Context
import android.content.Context.MODE_PRIVATE
import com.posthog.PostHogConfig
import com.posthog.PostHogPreferences

internal class PostHogSharedPreferences(context: Context, config: PostHogConfig) :
    PostHogPreferences {

    private val sharedPreferences = context.getSharedPreferences("posthog-android-${config.apiKey}", MODE_PRIVATE)

    private val lock = Any()

    override fun getValue(key: String, defaultValue: Any?): Any? {
        synchronized(lock) {
            return sharedPreferences.all[key] ?: defaultValue
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
                is Set<*> -> {
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
}
