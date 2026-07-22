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

    // Set to true when a draw arrives while a postDelayed is already in flight.
    // The pending draw is re-captured once the delayed snapshot fires, ensuring
    // that a screen change landing inside the throttle window is not silently lost.
    private val hasPendingDraw = AtomicBoolean(false)

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
            if (!isThrottling.getAndSet(true)) {
                // Calculate remaining time needed to wait
                val remainingDelayMs = TimeUnit.NANOSECONDS.toMillis(delayNs - timeSinceLastExecution)
                mainHandler.handler.postDelayed({
                    val pendingAfter = hasPendingDraw.getAndSet(false)
                    executeAndReleaseThrottle(runnable)
                    // A draw arrived while we were waiting; take one more snapshot so
                    // no screen change that occurred inside the throttle window is lost.
                    if (pendingAfter) {
                        throttle(runnable)
                    }
                }, remainingDelayMs)
            } else {
                // A draw arrived while a postDelayed is already scheduled.
                // Mark it so the delayed lambda re-captures the current view tree after it fires.
                hasPendingDraw.set(true)
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
