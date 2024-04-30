package com.posthog.android.replay.internal

import com.posthog.android.internal.MainHandler
import com.posthog.internal.PostHogDateProvider
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class Debouncer(
    private val mainHandler: MainHandler,
    private val dateProvider: PostHogDateProvider,
    private val debouncerDelayMs: Long,
) {
    private var lastCall = 0L
    private val delayNs = TimeUnit.MILLISECONDS.toNanos(debouncerDelayMs)
    private val hasPendingRunnable = AtomicBoolean(false)

    /**
     * Debounces the given [runnable] by delaying its execution until [delayNs] has passed since the last call.
     */
    internal fun debounce(runnable: Runnable) {
        if (lastCall == 0L) {
            lastCall = dateProvider.nanoTime()
        }

        val timePassedSinceLastExecution = dateProvider.nanoTime() - lastCall
        if (timePassedSinceLastExecution >= delayNs) {
            if (!hasPendingRunnable.get()) {
                execute(runnable)
            }
        } else {
            if (!hasPendingRunnable.getAndSet(true)) {
                mainHandler.handler.postDelayed({
                    try {
                        execute(runnable)
                    } finally {
                        hasPendingRunnable.set(false)
                    }
                }, debouncerDelayMs)
            }
        }
    }

    private fun execute(runnable: Runnable) {
        runnable.run()
        lastCall = dateProvider.nanoTime()
    }
}
