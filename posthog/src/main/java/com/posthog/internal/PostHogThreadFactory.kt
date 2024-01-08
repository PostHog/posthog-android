package com.posthog.internal

import com.posthog.PostHogInternal
import java.util.concurrent.ThreadFactory

/**
 * A Thread factory for Executors
 * @property threadName the threadName
 */
@PostHogInternal
public class PostHogThreadFactory(private val threadName: String) : ThreadFactory {
    override fun newThread(runnable: Runnable): Thread {
        return Thread(runnable).apply {
            isDaemon = true
            name = threadName
        }
    }
}
