package com.posthog

/**
 * Logs the messages using System.out only if config.debug is enabled
 * @property config the Config
 */
@PostHogInternal
public class PostHogPrintLogger(private val config: PostHogConfig) : PostHogLogger {
    override fun log(message: String) {
        if (!config.debug) {
            return
        }
        println(message)
    }
}
