package com.posthog.android.internal

import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import com.posthog.android.PostHogAndroidConfig
import java.lang.ref.WeakReference
import java.util.Date

/** One candidate and one timer globally per installation, with at most ten full snapshots/second. */
internal class InteractionDeadDetector(
    private val config: PostHogAndroidConfig,
    private val mainHandler: MainHandler,
    private val generation: () -> Long?,
    private val emit: (Long, Map<String, Any>, Date) -> Unit,
    private val now: () -> Long = SystemClock::uptimeMillis,
    private val resolver: InteractionTargetResolver = InteractionTargetResolver(),
) {
    private class Pending(
        root: View,
        val target: InteractionTarget,
        val generation: Long,
        val screenX: Float,
        val screenY: Float,
        val properties: Map<String, Any>,
        val timestamp: Long,
        val started: Long,
        val snapshot: InteractionResponseSnapshot,
        val baseline: ByteArray,
    ) {
        val root = WeakReference(root)
        var checked = started
    }

    private var pending: Pending? = null
    private val timer = Runnable { checkResponse() }
    private val drawListener =
        ViewTreeObserver.OnPreDrawListener {
            val candidate = pending
            if (candidate != null && now() - candidate.checked >= 100) checkResponse()
            true
        }
    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (!focused) cancel() }

    fun cancel() {
        val previous = pending
        pending = null
        mainHandler.handler.removeCallbacks(timer)
        previous?.root?.get()?.viewTreeObserver?.takeIf { it.isAlive }?.let {
            it.removeOnPreDrawListener(drawListener)
            it.removeOnWindowFocusChangeListener(focusListener)
        }
    }

    fun begin(
        root: View,
        target: InteractionTarget,
        epoch: Long,
        x: Float,
        y: Float,
        properties: Map<String, Any>,
    ) {
        cancel()
        if (!config.captureDeadClicks || target.repetitive || !root.isAttachedToWindow || generation() != epoch) return
        val snapshot = InteractionResponseSnapshot()
        val baseline = snapshot.take(root) ?: return
        val observer = root.viewTreeObserver
        if (!observer.isAlive) return
        pending = Pending(root, target, epoch, x, y, properties.toMap(), config.dateProvider.currentTimeMillis(), now(), snapshot, baseline)
        observer.addOnPreDrawListener(drawListener)
        observer.addOnWindowFocusChangeListener(focusListener)
        mainHandler.handler.postDelayed(timer, 100)
    }

    // Also called immediately after dispatch, catching synchronous content changes before a frame.
    fun checkResponse() {
        val candidate = pending ?: return
        try {
            val root = candidate.root.get()
            if (!config.captureDeadClicks || generation() != candidate.generation ||
                root == null || !root.isAttachedToWindow ||
                root.windowVisibility != View.VISIBLE || !root.isShown
            ) {
                cancel()
                return
            }
            val target = resolver.resolve(root, candidate.screenX, candidate.screenY)
            val snapshot = candidate.snapshot.take(root)
            if (target == null || !target.sameTarget(candidate.target) || target.repetitive ||
                target.elements != candidate.target.elements || snapshot == null || !snapshot.contentEquals(candidate.baseline)
            ) {
                cancel()
                return
            }
            val time = now()
            val elapsed = time - candidate.started
            if (elapsed < 0 || elapsed > 4000 || time - candidate.checked > 500) {
                // Do not infer liveness across main-thread stalls or suspension.
                cancel()
            } else if (elapsed >= 3000) {
                cancel()
                if (generation() == candidate.generation) {
                    emit(
                        candidate.generation,
                        candidate.properties +
                            mapOf(
                                "\$dead_click_event_timestamp" to candidate.timestamp,
                                "\$dead_click_absolute_delay_ms" to elapsed,
                                "\$dead_click_absolute_timeout" to true,
                            ),
                        Date(candidate.timestamp),
                    )
                }
            } else {
                candidate.checked = time
                mainHandler.handler.removeCallbacks(timer)
                mainHandler.handler.postDelayed(timer, 100)
            }
        } catch (_: Throwable) {
            cancel()
        }
    }
}
