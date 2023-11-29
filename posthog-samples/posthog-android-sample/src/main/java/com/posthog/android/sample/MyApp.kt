package com.posthog.android.sample

import android.app.Application
import android.os.StrictMode
import com.posthog.PostHogOnFeatureFlags
import com.posthog.PostHogPropertiesSanitizer
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        enableStrictMode()

        val config = PostHogAndroidConfig("_6SG-F7I1vCuZ-HdJL3VZQqjBlaSb1_20hDPwqMNnGI").apply {
            debug = true
            flushAt = 5
            maxBatchSize = 5
            onFeatureFlags = PostHogOnFeatureFlags { print("feature flags loaded") }
            propertiesSanitizer = PostHogPropertiesSanitizer { properties ->
                properties.apply {
                    remove("\$device_name")
                }
            }
        }
        PostHogAndroid.setup(this, config)
    }

    private fun enableStrictMode() {
        if (BuildConfig.DEBUG) {
            val threadPolicyBuilder = StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog()
            val vmPolicyBuilder = StrictMode.VmPolicy.Builder().detectAll().penaltyLog()

            StrictMode.setThreadPolicy(threadPolicyBuilder.build())
            StrictMode.setVmPolicy(vmPolicyBuilder.build())
        }
    }
}
