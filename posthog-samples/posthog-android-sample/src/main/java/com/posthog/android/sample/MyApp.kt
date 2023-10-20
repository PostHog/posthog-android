package com.posthog.android.sample

import android.app.Application
import com.posthog.PostHogOnFeatureFlags
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val host = "https://tadpole-wanted-indirectly.ngrok-free.app"
        val apiKey = "phc_Kddg1MpLcrZmeVynOSC2V9REk5ssjWGNYip53M25gh8"
        val config = PostHogAndroidConfig(apiKey, host = host).apply {
            debug = true
            flushAt = 1
            maxBatchSize = 5
            onFeatureFlags = PostHogOnFeatureFlags { print("feature flags loaded") }
            captureApplicationLifecycleEvents = false
            captureScreenViews = false
        }
        PostHogAndroid.setup(this, config)
    }
}
