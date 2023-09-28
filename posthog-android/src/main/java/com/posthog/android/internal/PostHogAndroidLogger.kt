package com.posthog.android.internal

import android.util.Log
import com.posthog.PostHogLogger
import com.posthog.android.PostHogAndroidConfig

/**
 * Logs the messages using Logcat only if config.debug is enabled
 * @property config the Config
 */
internal class PostHogAndroidLogger(private val config: PostHogAndroidConfig) : PostHogLogger {
    override fun log(message: String) {
        if (config.debug) {
            Log.println(Log.DEBUG, "PostHog", message)
        }
    }
}
