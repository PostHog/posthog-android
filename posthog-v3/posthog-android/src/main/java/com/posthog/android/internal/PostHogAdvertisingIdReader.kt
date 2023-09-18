package com.posthog.android.internal

import android.content.Context

// GetAdvertisingIdTask, Utils
internal class PostHogAdvertisingIdReader(context: Context) {
    fun getGooglePlayServicesAdvertisingID(): Pair<String, Boolean>? {
        return null
    }

    fun getAmazonFireAdvertisingID(): Pair<String, Boolean>? {
        return null
    }

    fun getDeviceId(): String? {
        // TODO depends on DEFAULT_COLLECT_DEVICE_ID
        return null
    }
}
