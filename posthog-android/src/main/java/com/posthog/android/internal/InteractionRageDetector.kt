package com.posthog.android.internal

import android.view.View
import java.lang.ref.WeakReference
import java.util.ArrayDeque

/** At most four entries; a continuous burst emits once until a quiet second or a target change. */
internal class InteractionRageDetector {
    private data class Tap(val time: Long, val x: Float, val y: Float)

    private val taps = ArrayDeque<Tap>(4)
    private var target: InteractionTarget? = null
    private var root = WeakReference<View>(null)
    private var generation: Long? = null
    private var lastTime = 0L
    private var emitted = false

    fun reset() {
        taps.clear()
        target = null
        root.clear()
        generation = null
        emitted = false
    }

    fun tap(
        view: View,
        current: InteractionTarget,
        epoch: Long,
        time: Long,
        x: Float,
        y: Float,
    ): Boolean {
        if (current.repetitive || !x.isFinite() || !y.isFinite()) {
            reset()
            return false
        }
        if (root.get() !== view || target?.sameTarget(current) != true || generation != epoch ||
            time < lastTime || time - lastTime > 1000
        ) {
            reset()
        }
        root = WeakReference(view)
        target = current
        generation = epoch
        lastTime = time
        if (emitted) return false
        while (taps.isNotEmpty() && time - taps.first.time > 1000) taps.removeFirst()
        // Compare all points, not just consecutive taps: slow spatial drift is not a burst.
        while (taps.any { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) > 50f * 50f }) taps.removeFirst()
        taps.addLast(Tap(time, x, y))
        if (taps.size < 4) return false
        taps.clear()
        emitted = true
        return true
    }
}
