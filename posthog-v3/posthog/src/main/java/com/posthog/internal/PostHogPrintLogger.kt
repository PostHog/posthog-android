package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogLogger

internal class PostHogPrintLogger(private val config: PostHogConfig) : PostHogLogger {
    override fun log(message: String) {
        if (!config.debug) {
            return
        }
        println(message)
    }
}
