package com.posthog.android.replay.internal

import android.view.ViewTreeObserver
import com.posthog.internal.replay.RRWireframe

// if you add any new property, remember to clear the state from resetViewSnapshotStates
internal class ViewTreeSnapshotStatus(
    val listener: NextDrawListener,
    val layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null,
    var sentFullSnapshot: Boolean = false,
    var sentMetaEvent: Boolean = false,
    var keyboardVisible: Boolean = false,
    var lastSnapshot: RRWireframe? = null,
    val drawState: WindowDrawState = WindowDrawState(),
)

// Per-window draw-dirty tracking for the screenshot capture guard. Scoped to one decor view
// because PixelCopy copies a single window's surface and masks are computed from that window's
// own tree, so a draw in one window says nothing about another window's mask alignment.
// Written on the main thread (draw/layout listeners), read on the capture threads.
internal class WindowDrawState {
    @Volatile
    var isOnDrawnCalled: Boolean = false

    // True if a layout pass ran in this window during the current capture window (set by the
    // decor view's OnGlobalLayoutListener, cleared per capture). A layout pass means mask
    // geometry may have shifted, so the frame must be discarded to avoid a PII leak.
    @Volatile
    var didLayoutSinceReset: Boolean = false

    // Both flags gate the same capture guard and must always be reset together.
    fun reset() {
        isOnDrawnCalled = false
        didLayoutSinceReset = false
    }
}
