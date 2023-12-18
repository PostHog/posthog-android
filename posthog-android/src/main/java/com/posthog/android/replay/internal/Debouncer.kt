package com.posthog.android.replay.internal

import com.posthog.android.internal.MainHandler
import com.posthog.internal.PostHogDateProvider
import java.util.concurrent.TimeUnit

internal class Debouncer(
    private val mainHandler: MainHandler,
    private val dateProvider: PostHogDateProvider,
) {
    private var lastCall = 0L
    private val oneFrameNs = TimeUnit.MILLISECONDS.toNanos(ONE_FRAME_MS)

    internal fun debounce(runnable: Runnable) {
        if (lastCall == 0L) {
            lastCall = dateProvider.nanoTime()
        }

        mainHandler.handler.removeCallbacksAndMessages(null)
        val timePassedSinceLastExecution = dateProvider.nanoTime() - lastCall
        if (timePassedSinceLastExecution >= oneFrameNs) {
            execute(runnable)
        } else {
            mainHandler.handler.postDelayed({ execute(runnable) }, ONE_FRAME_MS)
        }
    }

    private fun execute(runnable: Runnable) {
        runnable.run()
        lastCall = dateProvider.nanoTime()
    }

    companion object {
        private const val ONE_FRAME_MS = 64L
    }
}
