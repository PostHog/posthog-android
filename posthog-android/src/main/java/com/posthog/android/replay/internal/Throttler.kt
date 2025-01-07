package com.posthog.android.replay.internal

import com.posthog.android.internal.MainHandler
import com.posthog.internal.PostHogDateProvider
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class Throttler(
    private val mainHandler: MainHandler,
    private val dateProvider: PostHogDateProvider,
    private val throttleDelayMs: Long,
) {
    private var lastCall = 0L
    private val delayNs = TimeUnit.MILLISECONDS.toNanos(throttleDelayMs)
    private val isThrottling = AtomicBoolean(false)

    /**
     * Debounces the given [runnable] by delaying its execution until [delayNs] has passed since the last call.
     */
    internal fun debounce(runnable: Runnable) {
        val currentTime = dateProvider.nanoTime()

        // Check if enough time has passed since the last execution
        val timeSinceLastExecution = currentTime - lastCall
        if (timeSinceLastExecution >= delayNs) {
            // Execute immediately if enough time has passed
            execute(runnable)
        } else {
            // If already throttling, ignore additional calls
            if (!isThrottling.getAndSet(true)) {
                mainHandler.handler.postDelayed({
                    try {
                        execute(runnable)
                    } finally {
                        isThrottling.set(false) // Reset throttling after delay
                    }
                }, throttleDelayMs)
            }
        }
    }

    private fun execute(runnable: Runnable) {
        runnable.run()
        lastCall = dateProvider.nanoTime()
    }
}
