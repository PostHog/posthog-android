package com.posthog.android.replay.internal

import android.graphics.Rect
import android.view.ViewTreeObserver
import com.posthog.internal.replay.RRWireframe
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val CONSECUTIVE_DISCARD_WARNING_THRESHOLD: Int = 3
private const val COMPOSE_ROOT_RECHECK_INTERVAL_NANOS: Long = 1_000_000_000

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
    var isOnlyAnimationRedraw: Boolean = false

    @Volatile
    var didLayoutSinceReset: Boolean = false

    @Volatile
    var isLegacyCaptureActive: Boolean = false
        private set

    // Whether this window's view tree is rooted in Jetpack Compose. Detected once from the tree,
    // then cached, so the redraw handling does not re-walk the tree on every draw.
    @Volatile
    var composeRooted: Boolean? = null

    @Volatile
    private var lastComposeRootCheckNanos: Long = 0

    // A "not Compose" verdict is cleared by every layout pass so lazily mounted Compose is still
    // picked up, but the check walks the whole view tree, so rate-limit it rather than re-walking
    // on the first draw after every layout.
    fun shouldRecheckComposeRoot(nowNanos: Long): Boolean {
        if (lastComposeRootCheckNanos != 0L &&
            nowNanos - lastComposeRootCheckNanos < COMPOSE_ROOT_RECHECK_INTERVAL_NANOS
        ) {
            return false
        }
        lastComposeRootCheckNanos = nowNanos
        return true
    }

    // An indefinite verdict must not spend the re-check budget: it stays uncached and retries on
    // the next draw.
    fun clearComposeRootCheck() {
        lastComposeRootCheckNanos = 0
    }

    // Screenshots discarded in a row for mask safety. Incremented from the capture executor
    // thread and the PixelCopy callback thread, reset from the main thread — plain @Volatile
    // doesn't make `+1` atomic across those writers, hence AtomicInteger.
    private val consecutiveScreenshotDiscards = AtomicInteger(0)

    // Cleared alongside the counter so the warning fires once per run of discards, not once
    // per session and not on every discard past the threshold.
    private val discardWarningFired = AtomicBoolean(false)

    // Increments the discard streak and returns true exactly once per run — when the streak
    // first reaches (or, after a lost increment, jumps past) the warning threshold.
    fun recordScreenshotDiscard(): Boolean {
        val discards = consecutiveScreenshotDiscards.incrementAndGet()
        return discards >= CONSECUTIVE_DISCARD_WARNING_THRESHOLD && discardWarningFired.compareAndSet(false, true)
    }

    fun resetScreenshotDiscards() {
        consecutiveScreenshotDiscards.set(0)
        discardWarningFired.set(false)
    }

    fun resetSnapshotState() {
        composeRooted = null
        lastComposeRootCheckNanos = 0
        resetScreenshotDiscards()
        invalidateMaskCapture()
    }

    private val captureLock = Any()
    private var nextCaptureId: Long = 0
    private var activeCapture: ActiveMaskCapture? = null

    fun reset() {
        isOnDrawnCalled = false
        isOnlyAnimationRedraw = false
        didLayoutSinceReset = false
    }

    fun beginLegacyCapture() {
        reset()
        isLegacyCaptureActive = true
    }

    fun finishLegacyCapture() {
        isLegacyCaptureActive = false
        reset()
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
        isOnlyAnimationRedraw = false
    }

    fun recordLegacyAnimationRedraw(isOnlyAnimationRedraw: Boolean) {
        this.isOnlyAnimationRedraw = isOnlyAnimationRedraw
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
        // Compose can be mounted lazily, so a "not Compose" verdict must be re-checked after a
        // layout. A "Compose" verdict never needs re-checking: the window simply stays on the
        // verified path (the safe direction), avoiding a tree re-walk on every layout pass.
        if (composeRooted == false) {
            composeRooted = null
        }
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
