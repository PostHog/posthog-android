package com.posthog.android.replay.internal

import android.os.Handler
import android.os.Looper
import java.util.concurrent.TimeUnit

internal class Debouncer {
    private val handler: Handler = Handler(Looper.getMainLooper())

    private var lastCall = 0L
    private val oneFrameNs = TimeUnit.MILLISECONDS.toNanos(ONE_FRAME_MS)
//    private val runnables = WeakHashMap<Runnable, Any?>()

    internal fun debounce(runnable: Runnable) {
        if (lastCall == 0L) {
            lastCall = System.nanoTime()
        }

        handler.removeCallbacksAndMessages(null)
        val timePassedSinceLastExecution = System.nanoTime() - lastCall
        if (timePassedSinceLastExecution >= oneFrameNs) {
            execute(runnable)
        } else {
//            runnables[runnable] = null
            handler.postDelayed({ execute(runnable) }, ONE_FRAME_MS)
        }
    }

    private fun execute(runnable: Runnable) {
        runnable.run()
//        runnables.remove(runnable)
        lastCall = System.nanoTime()
    }

    companion object {
        private const val ONE_FRAME_MS = 64L
    }
}
