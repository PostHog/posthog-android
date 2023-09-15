package com.posthog.android.internal

import android.content.Context
import android.content.Context.MODE_PRIVATE
import com.posthog.PostHogConfig

internal class PostHogSharedPreferences(context: Context, config: PostHogConfig) {

    private val sharedPreferences = context.getSharedPreferences("posthog-android-${config.apiKey}", MODE_PRIVATE)
    private val packageInfo = getPackageInfo(context, config)

    fun getValue(key: String, defaultValue: Any? = null): Any? {
        return sharedPreferences.all[key] ?: defaultValue
    }

    fun setValue(key: String, value: String) {
        val edit = sharedPreferences.edit()
        edit.putString(key, value)
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
