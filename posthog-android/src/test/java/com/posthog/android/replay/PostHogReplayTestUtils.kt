package com.posthog.android.replay

import android.os.Looper
import android.view.View
import android.view.Window
import org.robolectric.Shadows.shadowOf
import java.lang.ref.WeakReference
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal fun PostHogReplayIntegration.captureSnapshot(
    viewRef: WeakReference<View>,
    windowRef: WeakReference<Window>,
    forceScreenshot: Boolean = false,
): Boolean {
    if (Looper.myLooper() != Looper.getMainLooper()) {
        return generateSnapshot(viewRef, windowRef, forceScreenshot)
    }
    val executor = Executors.newSingleThreadExecutor()
    try {
        val future = executor.submit(Callable { generateSnapshot(viewRef, windowRef, forceScreenshot) })
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            try {
                return future.get(20, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // Main-thread view reads need the paused test looper to advance.
            }
        }
        return future.get(0, TimeUnit.MILLISECONDS)
    } finally {
        executor.shutdownNow()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }
}
