package com.posthog.android.replay.internal

import com.posthog.android.internal.MainHandler
import com.posthog.internal.PostHogDateProvider
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class Throttler(
    private val mainHandler: MainHandler,
    private val dateProvider: PostHogDateProvider,
    throttleDelayMs: Long,
) {
    private var lastCall = 0L
    private val delayNs = TimeUnit.MILLISECONDS.toNanos(throttleDelayMs)
    private val isThrottling = AtomicBoolean(false)

    /**
     * Throttles the given [runnable] by delaying its execution until [delayNs] has passed since the last call.
     */
    internal fun throttle(runnable: Runnable) {
        val currentTime = dateProvider.nanoTime()

        // Check if enough time has passed since the last execution
        val timeSinceLastExecution = currentTime - lastCall
        if (timeSinceLastExecution >= delayNs) {
            // Execute immediately if enough time has passed and not already throttling
            if (!isThrottling.getAndSet(true)) {
                executeAndReleaseThrottle(runnable)
            }
        } else {
            // If already throttling, ignore additional calls
            if (!isThrottling.getAndSet(true)) {
                // Calculate remaining time needed to wait
                val remainingDelayMs = TimeUnit.NANOSECONDS.toMillis(delayNs - timeSinceLastExecution)
                mainHandler.handler.postDelayed({
                    executeAndReleaseThrottle(runnable)
                }, remainingDelayMs)
            }
        }
    }

    private fun executeAndReleaseThrottle(runnable: Runnable) {
        try {
            lastCall = dateProvider.nanoTime()
            runnable.run()
        } finally {
            isThrottling.set(false)
        }
    }
}
