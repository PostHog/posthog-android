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
    val armedDrawCount: Long,
    // Null until the pre-walk fixes it via setBaseline.
    var baselineRects: List<Rect>? = null,
    var invalid: Boolean = false,
    var drawSamplesInProgress: Int = 0,
)

internal class DrawSampleSession(
    val token: MaskCaptureToken,
    val compareBaseline: List<Rect>,
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
    private var activeCapture: ActiveMaskCapture? = null

    fun reset() {
        isOnDrawnCalled = false
        didLayoutSinceReset = false
    }

    // Written only on the main thread (single writer), so the lock-free increment is safe.
    // Monotone and never reset: captures compare snapshots of it, not absolute values.
    @Volatile
    private var drawCount: Long = 0

    // recordDraw is a draw's very first instruction, so this counter (checked in setBaseline)
    // classifies the draw deterministically as during-pre-walk or post-baseline. Relying on
    // beginDrawSample alone would be racy: a during-pre-walk draw could lose the lock race to
    // setBaseline and be misclassified as post-baseline.
    fun recordDraw() {
        isOnDrawnCalled = true
        drawCount++
    }

    // Arms detection BEFORE the pre-walk, so a draw overlapping it cannot go unnoticed.
    fun beginMaskCapture(): MaskCaptureToken {
        synchronized(captureLock) {
            val token = MaskCaptureToken(++nextCaptureId)
            activeCapture = ActiveMaskCapture(token, armedDrawCount = drawCount)
            return token
        }
    }

    // Fixes the pre-walk's rects as the capture baseline. Returns false when the capture is
    // already unkeepable (layout, or a draw since arming), so the caller can skip the bitmap
    // and PixelCopy work entirely.
    fun setBaseline(
        token: MaskCaptureToken,
        rects: List<Rect>,
    ): Boolean {
        synchronized(captureLock) {
            val capture = activeCapture
            if (capture?.token != token) {
                return false
            }
            if (drawCount != capture.armedDrawCount) {
                capture.invalid = true
            }
            capture.baselineRects = rects.map(::Rect)
            return !capture.invalid && !didLayoutSinceReset
        }
    }

    // Null when there is no capture to sample, or its verdict is already sealed as discard —
    // then the per-frame mask walk would be wasted work. A draw before the baseline is fixed
    // invalidates outright: the pre-walk may already have read this draw's tree state while
    // PixelCopy can still freeze the frame before it, so agreement would prove nothing.
    fun beginDrawSample(): DrawSampleSession? {
        synchronized(captureLock) {
            val capture = activeCapture ?: return null
            if (capture.invalid || didLayoutSinceReset) {
                return null
            }
            val baseline = capture.baselineRects
            if (baseline == null) {
                capture.invalid = true
                return null
            }
            capture.drawSamplesInProgress++
            return DrawSampleSession(capture.token, baseline)
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
            // baselineRects is null only when setBaseline never ran; fail closed then.
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
