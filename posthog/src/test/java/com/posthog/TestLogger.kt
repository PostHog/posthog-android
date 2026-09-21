package com.posthog

import com.posthog.internal.PostHogLogger

internal class TestLogger : PostHogLogger {
    val messages = mutableListOf<String>()
    val warnings = mutableListOf<String>()

    override fun log(message: String) {
        messages.add(message)
    }

    override fun logWarning(message: String) {
        warnings.add(message)
    }

    override fun isEnabled(): Boolean = true
}
