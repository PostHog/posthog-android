package com.posthog.android.internal

import android.util.Log
import com.posthog.PostHogConfig
import com.posthog.PostHogLogger

internal class PostHogAndroidLogger(private val config: PostHogConfig) : PostHogLogger {
    override fun log(message: String) {
        if (config.debug) {
            Log.println(Log.DEBUG, null, message)
        }
    }
}
