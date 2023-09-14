package com.posthog.internal

import java.util.concurrent.ThreadFactory

internal class PostHogThreadFactory(private val threadName: String) : ThreadFactory {
    override fun newThread(runnable: Runnable): Thread {
        return Thread(runnable).apply {
            isDaemon = true
            name = threadName
        }
    }
}
