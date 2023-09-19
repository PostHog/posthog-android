package com.posthog.android.internal

import android.content.Context
import android.content.Context.MODE_PRIVATE
import com.posthog.PostHogConfig
import com.posthog.PostHogPreferences

internal class PostHogSharedPreferences(context: Context, config: PostHogConfig) :
    PostHogPreferences {

    private val sharedPreferences = context.getSharedPreferences("posthog-android-${config.apiKey}", MODE_PRIVATE)
    private val packageInfo = getPackageInfo(context, config)

    override fun getValue(key: String, defaultValue: Any?): Any? {
        return sharedPreferences.all[key] ?: defaultValue
    }

    override fun setValue(key: String, value: Any) {
        val edit = sharedPreferences.edit()

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
        }

        edit.apply()
    }

    override fun clear() {
        val edit = sharedPreferences.edit()
        edit.clear()
        edit.apply()
    }

    fun init() {
        packageInfo?.let {
            // key= build
            val versionCode = getVersionCode(it)
            // key=version
            val versioName = it.versionName

            // TODO: send application installed or updated event
        }
    }
}
