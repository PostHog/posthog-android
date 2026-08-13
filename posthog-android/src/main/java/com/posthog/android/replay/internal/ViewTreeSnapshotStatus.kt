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

// Outcome of fixing a capture baseline via setBaseline.
internal enum class BaselineResult {
    // The baseline is fixed; draws from here on are verified against it.
    ARMED,

    // A draw overlapped the pre-walk, so the walked rects may be torn. Only this walk is
    // lost: a fresh pre-walk under a new capture can still arm cleanly.
    TORN_BY_DRAW,

    // A layout (which holds until the next capture reset) or an external invalidation
    // (stop or session reset) landed; re-arming must not resurrect either.
    UNKEEPABLE,
}

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

    // Fixes the pre-walk's rects as the capture baseline, reporting an unkeepable capture
    // before the caller pays for the bitmap and PixelCopy work.
    fun setBaseline(
        token: MaskCaptureToken,
        rects: List<Rect>,
    ): BaselineResult {
        synchronized(captureLock) {
            val capture = activeCapture
            if (capture?.token != token) {
                return BaselineResult.UNKEEPABLE
            }
            val drawLandedSinceArming = drawCount != capture.armedDrawCount
            if (drawLandedSinceArming) {
                capture.invalid = true
            }
            capture.baselineRects = rects.map(::Rect)
            return when {
                didLayoutSinceReset -> BaselineResult.UNKEEPABLE
                capture.invalid && !drawLandedSinceArming -> BaselineResult.UNKEEPABLE
                capture.invalid -> BaselineResult.TORN_BY_DRAW
                else -> BaselineResult.ARMED
            }
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
