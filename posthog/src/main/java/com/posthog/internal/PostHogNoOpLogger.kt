package com.posthog.internal

import com.posthog.PostHogInternal

/**
 * NoOp Logger
 */
@PostHogInternal
public class PostHogNoOpLogger : PostHogLogger {
    override fun log(message: String) {
    }

    override fun isEnabled(): Boolean = false
}
