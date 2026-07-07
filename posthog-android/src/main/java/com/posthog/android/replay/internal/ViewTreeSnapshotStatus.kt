package com.posthog.android.replay.internal

import android.view.View
import com.posthog.internal.replay.RRWireframe
import java.lang.ref.WeakReference

internal class ViewTreeSnapshotStatus(
    val listener: NextDrawListener,
    var sentFullSnapshot: Boolean = false,
    var sentMetaEvent: Boolean = false,
    var keyboardVisible: Boolean = false,
    var lastSnapshot: RRWireframe? = null,
) {
    // Per-decorView draw-dirty flags. Kept here (rather than on the integration) so a continuously
    // animating window (e.g. a dialog spinner) does not poison the capture of other windows.
    @Volatile
    var isOnDrawnCalled: Boolean = false

    @Volatile
    var isOnlyAnimationRedraw: Boolean = false

    // Animating-spinner tracking. Written on the main thread from onDraw but reset from whatever
    // thread ends a session, so @Volatile. The reference to the last located spinner lets onDraw
    // re-validate it cheaply instead of re-walking the tree every frame.
    @Volatile
    var lastAnimationProbeMs: Long = 0

    @Volatile
    var animatingProgressBar: WeakReference<View>? = null

    /**
     * Clears all mutable per-view state so the view starts a fresh session with a full snapshot.
     * Every mutable field above must be reset here — [ViewTreeSnapshotStatusTest] enforces it.
     */
    fun reset() {
        sentFullSnapshot = false
        sentMetaEvent = false
        keyboardVisible = false
        lastSnapshot = null
        isOnDrawnCalled = false
        isOnlyAnimationRedraw = false
        lastAnimationProbeMs = 0
        animatingProgressBar = null
    }
}
