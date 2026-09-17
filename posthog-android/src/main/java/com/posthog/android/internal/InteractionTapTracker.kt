package com.posthog.android.internal

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/** Snapshot coordinates before host dispatch, in window-local dp (the mobile touch schema). */
internal fun interactionPropertiesForTap(
    target: InteractionTarget,
    root: View,
    event: MotionEvent,
): Map<String, Any> {
    val screen = IntArray(2)
    val window = IntArray(2)
    root.getLocationOnScreen(screen)
    root.getLocationInWindow(window)
    val density = root.resources.displayMetrics.density
    check(density.isFinite() && density > 0f)
    return interactionProperties(
        target.elements,
        (event.rawX - screen[0] + window[0]) / density,
        (event.rawY - screen[1] + window[1]) / density,
    )
}

/** Recognizes single-pointer taps without consuming, copying or redispatching input. */
internal class InteractionTapTracker(
    private val resolver: InteractionTargetResolver = InteractionTargetResolver(),
) {
    private var downTarget: InteractionTarget? = null
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    fun reset() {
        downTarget = null
    }

    // Called before app dispatch, so the target and its properties precede synchronous UI mutation.
    fun onTouch(
        root: View,
        event: MotionEvent,
    ): InteractionTarget? {
        if (event.pointerCount != 1 || event.historySize > MAX_INTERACTION_NODES) {
            reset()
            return null
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                downTarget = resolver.resolve(root, event.rawX, event.rawY)
                downX = event.rawX
                downY = event.rawY
                downTime = event.eventTime
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val target = downTarget ?: return null
                val slop = ViewConfiguration.get(root.context).scaledTouchSlop
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                // Include batched move history: moving away and back is not a tap.
                val moved =
                    (0 until event.historySize).any {
                        val hx = event.getHistoricalX(it) - event.x + event.rawX - downX
                        val hy = event.getHistoricalY(it) - event.y + event.rawY - downY
                        hx * hx + hy * hy > slop * slop
                    }
                if (moved || dx * dx + dy * dy > slop * slop ||
                    event.eventTime < downTime || event.eventTime - downTime >= ViewConfiguration.getLongPressTimeout()
                ) {
                    reset()
                    return null
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    reset()
                    val current = resolver.resolve(root, event.rawX, event.rawY) ?: return null
                    return current.takeIf { target.sameTarget(it) }
                }
            }
            else -> reset()
        }
        return null
    }
}
