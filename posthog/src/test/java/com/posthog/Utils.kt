package com.posthog

import java.lang.RuntimeException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

public const val apiKey: String = "_6SG-F7I1vCuZ-HdJL3VZQqjBlaSb1_20hDPwqMNnGI"

public fun ExecutorService.shutdownAndAwaitTermination() {
    shutdown() // Disable new tasks from being submitted
    try {
        // Wait a while for existing tasks to terminate
        if (!awaitTermination(60, TimeUnit.SECONDS)) {
            shutdownNow() // Cancel currently executing tasks
            // Wait a while for tasks to respond to being cancelled
            if (!awaitTermination(
                    60,
                    TimeUnit.SECONDS,
                )
            ) {
                throw RuntimeException("Pool did not terminate")
            }
        }
    } catch (ie: InterruptedException) {
        // (Re-)Cancel if current thread also interrupted
        shutdownNow()
        // Preserve interrupt status
        Thread.currentThread().interrupt()
    }
}
