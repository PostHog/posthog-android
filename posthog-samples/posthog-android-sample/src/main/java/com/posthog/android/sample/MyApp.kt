package com.posthog.android.sample

import android.app.Application
import com.posthog.PostHogOnFeatureFlags
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
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
        }
        PostHogAndroid.setup(this, config)
    }
}
