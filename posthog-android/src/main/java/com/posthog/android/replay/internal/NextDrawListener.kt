// Inspired from https://github.com/square/curtains/blob/487bda6de00638c6decb3394b8a50bf83bed7496/curtains/src/main/java/curtains/internal/NextDrawListener.kt#L13

package com.posthog.android.replay.internal

import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import com.posthog.android.internal.MainHandler
import com.posthog.internal.PostHogDateProvider

internal class NextDrawListener(
    private val view: View,
    mainHandler: MainHandler,
    dateProvider: PostHogDateProvider,
    private val onDrawCallback: () -> Unit,
) : ViewTreeObserver.OnDrawListener {

    private val debounce = Debouncer(mainHandler, dateProvider)
    override fun onDraw() {
        debounce.debounce {
            onDrawCallback()
        }
    }

    private fun safelyRegisterForNextDraw() {
        // Prior to API 26, OnDrawListener wasn't merged back from the floating ViewTreeObserver into
        // the real ViewTreeObserver.
        // https://android.googlesource.com/platform/frameworks/base/+/9f8ec54244a5e0343b9748db3329733f259604f3
        view.viewTreeObserver?.let { viewTreeObserver ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || (viewTreeObserver.isAlive && view.isAttachedToWindow)) {
                viewTreeObserver.addOnDrawListener(this)
            }
        }
    }

    companion object {
        // only call if onDecorViewReady
        internal fun View.onNextDraw(mainHandler: MainHandler, dateProvider: PostHogDateProvider, onDrawCallback: () -> Unit): NextDrawListener {
            val nextDrawListener = NextDrawListener(this, mainHandler, dateProvider, onDrawCallback)
            nextDrawListener.safelyRegisterForNextDraw()
            return nextDrawListener
        }
    }
}
