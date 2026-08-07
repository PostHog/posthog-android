package com.posthog.android.replay.internal

import android.graphics.Rect
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

internal data class MaskCaptureToken(
    val id: Long,
)

private class ActiveMaskCapture(
    val token: MaskCaptureToken,
    val baselineRects: List<Rect>,
    var invalid: Boolean = false,
    var drawSamplesInProgress: Int = 0,
)

// Per-window because a draw in one window says nothing about another window's mask alignment.
// Written on the main thread and capture threads, with capture state guarded by captureLock.
internal class WindowDrawState {
    @Volatile
    var isOnDrawnCalled: Boolean = false

    @Volatile
    var didLayoutSinceReset: Boolean = false

    private val captureLock = Any()
    private var nextCaptureId: Long = 0
    private var drawGeneration: Long = 0
    private var activeCapture: ActiveMaskCapture? = null

    fun reset() {
        isOnDrawnCalled = false
        didLayoutSinceReset = false
    }

    fun recordDraw() {
        isOnDrawnCalled = true
        synchronized(captureLock) {
            drawGeneration++
        }
    }

    fun currentDrawGeneration(): Long {
        synchronized(captureLock) {
            return drawGeneration
        }
    }

    fun beginMaskCapture(
        rects: List<Rect>,
        expectedDrawGeneration: Long,
    ): MaskCaptureToken {
        synchronized(captureLock) {
            val token = MaskCaptureToken(++nextCaptureId)
            activeCapture =
                ActiveMaskCapture(
                    token,
                    rects.map(::Rect),
                    invalid = drawGeneration != expectedDrawGeneration,
                )
            return token
        }
    }

    fun beginDrawSample(): MaskCaptureToken? {
        synchronized(captureLock) {
            val capture = activeCapture ?: return null
            capture.drawSamplesInProgress++
            return capture.token
        }
    }

    fun recordMaskWalk(
        token: MaskCaptureToken,
        rects: List<Rect>,
        poisoned: Boolean,
    ) {
        synchronized(captureLock) {
            val capture = activeCapture
            if (capture?.token != token) {
                return
            }
            capture.drawSamplesInProgress--
            if (poisoned || capture.baselineRects != rects) {
                capture.invalid = true
            }
        }
    }

    fun recordLayout() {
        didLayoutSinceReset = true
        synchronized(captureLock) {
            activeCapture?.invalid = true
        }
    }

    fun finishMaskCapture(
        token: MaskCaptureToken,
        rects: List<Rect>,
        poisoned: Boolean,
    ): Boolean {
        synchronized(captureLock) {
            val capture = activeCapture
            if (capture?.token != token) {
                return false
            }
            activeCapture = null
            return !capture.invalid &&
                capture.drawSamplesInProgress == 0 &&
                !didLayoutSinceReset &&
                !poisoned &&
                capture.baselineRects == rects
        }
    }

    fun invalidateMaskCapture() {
        synchronized(captureLock) {
            activeCapture?.invalid = true
        }
    }

    fun cancelMaskCapture(token: MaskCaptureToken) {
        synchronized(captureLock) {
            if (activeCapture?.token == token) {
                activeCapture = null
            }
        }
    }
}
