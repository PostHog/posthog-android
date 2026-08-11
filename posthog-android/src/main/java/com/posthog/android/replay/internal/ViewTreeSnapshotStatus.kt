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

// internal (not private) so beginDrawSample can hand the baseline out together with the token.
internal class ActiveMaskCapture(
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

    // Null when a draw or layout already landed during the pre-walk: such a capture could
    // never be kept, so the caller can skip the bitmap and PixelCopy work entirely.
    fun beginMaskCapture(
        rects: List<Rect>,
        expectedDrawGeneration: Long,
    ): MaskCaptureToken? {
        synchronized(captureLock) {
            if (drawGeneration != expectedDrawGeneration || didLayoutSinceReset) {
                activeCapture = null
                return null
            }
            val token = MaskCaptureToken(++nextCaptureId)
            activeCapture = ActiveMaskCapture(token, rects.map(::Rect))
            return token
        }
    }

    // Null when there is no capture to sample, or its verdict is already sealed as discard —
    // then the per-frame mask walk would be wasted work.
    fun beginDrawSample(): ActiveMaskCapture? {
        synchronized(captureLock) {
            val capture = activeCapture ?: return null
            if (capture.invalid || didLayoutSinceReset) {
                return null
            }
            capture.drawSamplesInProgress++
            return capture
        }
    }

    fun recordMaskWalk(
        token: MaskCaptureToken,
        misaligned: Boolean,
    ) {
        synchronized(captureLock) {
            val capture = activeCapture
            if (capture?.token != token) {
                return
            }
            capture.drawSamplesInProgress--
            if (misaligned) {
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

    // True when the capture can no longer be kept, letting the PixelCopy callback skip its
    // post-copy walk. Monotone: nothing ever clears invalid, so a true here is final.
    fun isCaptureInvalid(token: MaskCaptureToken): Boolean {
        synchronized(captureLock) {
            val capture = activeCapture
            if (capture?.token != token) {
                return true
            }
            return capture.invalid || didLayoutSinceReset
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
