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

// Per-window because a draw in one window says nothing about another window's mask alignment.
// Written on the main thread, read on the capture threads.
internal class WindowDrawState {
    @Volatile
    var isOnDrawnCalled: Boolean = false

    @Volatile
    var didLayoutSinceReset: Boolean = false

    fun reset() {
        isOnDrawnCalled = false
        didLayoutSinceReset = false
    }
}
