package com.posthog

import java.util.concurrent.ThreadFactory

internal class PostHogThreadFactory : ThreadFactory {
    override fun newThread(p0: Runnable): Thread {
        return Thread(p0).apply {
            isDaemon = true
            name = "PostHogThreadFactory"
        }
    }
}
