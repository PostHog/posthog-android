package com.posthog

internal class PostHogPrintLogger(private val debug: Boolean) : PostHogLogger {
    override fun log(message: String) {
        if (!debug) {
            return
        }
        println(message)
    }
}
