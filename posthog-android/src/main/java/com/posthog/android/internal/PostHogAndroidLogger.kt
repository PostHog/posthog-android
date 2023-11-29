package com.posthog.android.internal

import android.util.Log
import com.posthog.android.PostHogAndroidConfig
import com.posthog.internal.PostHogLogger

/**
 * Logs the messages using Logcat only if config.debug is enabled
 * @property config the Config
 */
internal class PostHogAndroidLogger(private val config: PostHogAndroidConfig) : PostHogLogger {
    override fun log(message: String) {
        if (isEnabled()) {
            Log.println(Log.DEBUG, "PostHog", message)
        }
    }

    override fun isEnabled(): Boolean {
        return config.debug
    }
}
