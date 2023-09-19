package com.posthog.internal

import com.posthog.PostHogConfig

internal class PostHogStorage(private val config: PostHogConfig) {
    // TODO: move to disk cache instead of memory cache

    // File folder = context.getDir("posthog-disk-queue"/tag(apiKey), Context.MODE_PRIVATE);
    // /data/user/0/com.posthog.myapplication/app_posthog-disk-queue/_6SG-F7I1vCuZ-HdJL3VZQqjBlaSb1_20hDPwqMNnGI.tmp
    // instead of https://developer.android.com/reference/android/content/Context#getCacheDir()

    // application.getSharedPreferences("posthog-android", Context.MODE_PRIVATE) - legacy
    // context.getSharedPreferences("posthog-android-" + tag, MODE_PRIVATE)
    // eg posthog-android-_6SG-F7I1vCuZ-HdJL3VZQqjBlaSb1_20hDPwqMNnGI
    private val keyValues = mutableMapOf<String, Any>()
    private val lock = Any()

    fun getString(key: String, defaultValue: String? = null): String? {
        var value: String?
        synchronized(lock) {
            val tempValue = keyValues[key]
            value = if (tempValue != null && tempValue is String) tempValue else tempValue?.toString()
        }
        return value ?: defaultValue
    }

    fun setString(key: String, value: String) {
        synchronized(lock) {
            keyValues[key] = value
        }
    }
}
