package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogInternal

/**
 * Logs the messages using System.out only if config.debug is enabled
 * @property config the Config
 */
@PostHogInternal
public class PostHogPrintLogger(private val config: PostHogConfig) : PostHogLogger {
    override fun log(message: String) {
        // isEnabled can be abstracted in another class (refactor needed).
        if (isEnabled()) {
            println(message)
        }
    }

    override fun isEnabled(): Boolean {
        return config.debug
    }
}
