package com.posthog.android.sample

import android.app.Application
import com.posthog.PostHogOnFeatureFlags
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val config = PostHogAndroidConfig("_6SG-F7I1vCuZ-HdJL3VZQqjBlaSb1_20hDPwqMNnGI").apply {
            debug = true
            flushAt = 5
            maxBatchSize = 5
            onFeatureFlags = PostHogOnFeatureFlags { print("feature flags loaded") }
        }
        PostHogAndroid.setup(this, config)
    }
}
