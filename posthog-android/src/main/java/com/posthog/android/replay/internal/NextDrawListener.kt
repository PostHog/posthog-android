package com.posthog.android.replay.internal

import android.os.Build
import android.view.View
import android.view.ViewTreeObserver

internal class NextDrawListener(private val view: View, private val onDrawCallback: () -> Unit): ViewTreeObserver.OnDrawListener {

    private val debounce = Debouncer()
    override fun onDraw() {
        debounce.debounce {
            onDrawCallback()
        }
    }

    private fun safelyRegisterForNextDraw() {
        // Prior to API 26, OnDrawListener wasn't merged back from the floating ViewTreeObserver into
        // the real ViewTreeObserver.
        // https://android.googlesource.com/platform/frameworks/base/+/9f8ec54244a5e0343b9748db3329733f259604f3
        if (Build.VERSION.SDK_INT >= 26 || (view.viewTreeObserver.isAlive && view.isAttachedToWindow)) {
            view.viewTreeObserver.addOnDrawListener(this)
        }
    }

    companion object {
        // only call if onDecorViewReady
        internal fun View.onNextDraw(onDrawCallback: () -> Unit): NextDrawListener {
            val nextDrawListener = NextDrawListener(this, onDrawCallback)
            nextDrawListener.safelyRegisterForNextDraw()
            return nextDrawListener
        }
    }
}
