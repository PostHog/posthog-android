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
            maxBatchSize = 2
//            flushIntervalSeconds = 5
            onFeatureFlags = PostHogOnFeatureFlags { featureFlags -> print("has flags: ${featureFlags != null}") }
        }
        PostHogAndroid.setup(this, config)

//        val config2 = PostHogConfig("test")
//        val postHog = PostHog.with(config2)
//        postHog.capture("testConfig2")
    }
}
