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

        // Demo:
//        val apiKey = "_6SG-F7I1vCuZ-HdJL3VZQqjBlaSb1_20hDPwqMNnGI"
        // ManoelTesting:
        val apiKey = "phc_QFbR1y41s5sxnNTZoyKG2NJo2RlsCIWkUfdpawgb40D"
        // PaulKey
//        val apiKey = "phc_GavhjwMwc75N4HsaLjMTEvH8Kpsz70rZ3N0E9ho89YJ"
//        val config = PostHogAndroidConfig(apiKey, host = "https://3727-86-27-112-156.ngrok-free.app").apply {
        val config =
            PostHogAndroidConfig(apiKey).apply {
                debug = true
                flushAt = 1
                captureDeepLinks = false
                captureApplicationLifecycleEvents = false
                captureScreenViews = false
                sessionReplay = false
                preloadFeatureFlags = true
                onFeatureFlags = PostHogOnFeatureFlags { print("feature flags loaded") }
                propertiesSanitizer =
                    PostHogPropertiesSanitizer { properties ->
                        properties.apply {
//                    remove("\$device_name")
                        }
                    }
                sessionReplayConfig.maskAllTextInputs = false
                sessionReplayConfig.maskAllImages = false
                sessionReplayConfig.captureLogcat = true
                sessionReplayConfig.screenshot = true
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
