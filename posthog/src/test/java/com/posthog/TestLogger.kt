package com.posthog

import com.posthog.internal.PostHogLogger

internal class TestLogger : PostHogLogger {
    val messages = mutableListOf<String>()

    override fun log(message: String) {
        messages.add(message)
    }

    override fun isEnabled(): Boolean = true
}
