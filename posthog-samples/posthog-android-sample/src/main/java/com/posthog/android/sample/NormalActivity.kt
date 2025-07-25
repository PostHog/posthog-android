package com.posthog.android.sample

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import com.posthog.PersonProfiles.ALWAYS
import com.posthog.PostHogInterface
import com.posthog.PostHogOnErrorFeatureFlags
import com.posthog.PostHogOnFeatureFlags
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.replay.PostHogSessionReplayConfig

class NormalActivity : ComponentActivity() {

    private var postHog: PostHogInterface? = null

    private fun getConfig() = PostHogAndroidConfig(
        apiKey = "KEY", //todo set KEY
        host = "HOST",  //todo set HOST
        captureApplicationLifecycleEvents = true,
        captureDeepLinks = false,
        captureScreenViews = false,
        sessionReplayConfig = PostHogSessionReplayConfig()
    ).apply {
        sendFeatureFlagEvent = true
        preloadFeatureFlags = true
        debug = BuildConfig.DEBUG
        personProfiles = ALWAYS

        onErrorLoadedFlags = PostHogOnErrorFeatureFlags{
            Log.d("PosthogTest", "onFeatureFlags error = $it")
        }
        onFeatureFlags = PostHogOnFeatureFlags {
            Log.d("PosthogTest", "onFeatureFlags loaded")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.normal_activity)
        postHog = PostHogAndroid.with(this, getConfig())
    }
}
