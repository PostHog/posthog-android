package com.posthog

@PostHogInternal
public class PostHogPrintLogger(private val config: PostHogConfig) : PostHogLogger {
    override fun log(message: String) {
        if (!config.debug) {
            return
        }
        println(message)
    }
}
