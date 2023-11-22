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

        // Demo _6SG-F7I1vCuZ-HdJL3VZQqjBlaSb1_20hDPwqMNnGI
        // ManoelTesting
//        phc_pQ70jJhZKHRvDIL5ruOErnPy6xiAiWCqlL4ayELj4X8
        val config = PostHogAndroidConfig("phc_pQ70jJhZKHRvDIL5ruOErnPy6xiAiWCqlL4ayELj4X8").apply {
            debug = true
            flushAt = 1
            captureDeepLinks = false
            captureApplicationLifecycleEvents = false
            captureScreenViews = true
            sessionReplay = true
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
