package com.posthog.android.sample

import android.app.Application
import com.posthog.PostHog
import com.posthog.PostHogConfig

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val config = PostHogConfig("_6SG-F7I1vCuZ-HdJL3VZQqjBlaSb1_20hDPwqMNnGI").apply {
            debug = true
//            flushIntervalSeconds = 5
//            flushAt = 1
        }
        PostHog.setup(config)

//        val config2 = PostHogConfig("test")
//        val postHog = PostHog.with(config2)
//        postHog.capture("testConfig2")
    }
}
