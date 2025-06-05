// Inspired from https://github.com/square/curtains/blob/487bda6de00638c6decb3394b8a50bf83bed7496/curtains/src/main/java/curtains/internal/NextDrawListener.kt#L13

package com.posthog.android.replay.internal

import android.view.View
import android.view.ViewTreeObserver
import com.posthog.android.internal.MainHandler
import com.posthog.internal.PostHogDateProvider

internal class NextDrawListener(
    private val view: View,
    mainHandler: MainHandler,
    dateProvider: PostHogDateProvider,
    throttleDelayMs: Long,
    private val onDrawCallback: () -> Unit,
    private val onDrawThrottlerCallback: () -> Unit,
) : ViewTreeObserver.OnDrawListener {
    private val throttler = Throttler(mainHandler, dateProvider, throttleDelayMs)

    override fun onDraw() {
        onDrawCallback()
        throttler.throttle {
            onDrawThrottlerCallback()
        }
    }

    private fun safelyRegisterForNextDraw() {
        if (view.isAlive()) {
            view.viewTreeObserver?.addOnDrawListener(this)
        }
    }

    internal companion object {
        // only call if onDecorViewReady
        internal fun View.onNextDraw(
            mainHandler: MainHandler,
            dateProvider: PostHogDateProvider,
            throttleDelayMs: Long,
            onDrawCallback: () -> Unit,
            onDrawThrottlerCallback: () -> Unit,
        ): NextDrawListener {
            val nextDrawListener =
                NextDrawListener(this, mainHandler, dateProvider, throttleDelayMs, onDrawThrottlerCallback, onDrawCallback)
            nextDrawListener.safelyRegisterForNextDraw()
            return nextDrawListener
        }
    }
}

internal fun View.isAliveAndAttachedToWindow(): Boolean {
    // Prior to API 26, OnDrawListener wasn't merged back from the floating ViewTreeObserver into
    // the real ViewTreeObserver.
    // https://android.googlesource.com/platform/frameworks/base/+/9f8ec54244a5e0343b9748db3329733f259604f3
    return isAlive() && isAttachedToWindow
}

internal fun View.isAlive(): Boolean {
    return viewTreeObserver?.isAlive == true
}
