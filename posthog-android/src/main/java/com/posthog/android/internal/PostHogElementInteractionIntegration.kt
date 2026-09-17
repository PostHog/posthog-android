package com.posthog.android.internal

import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager.LayoutParams.FLAG_SECURE
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.posthog.PostHog
import com.posthog.PostHogCaptureContextReceiver
import com.posthog.PostHogCaptureGuard
import com.posthog.PostHogIntegration
import com.posthog.PostHogInterface
import com.posthog.android.PostHogAndroidConfig
import curtains.Curtains
import curtains.OnRootViewsChangedListener
import curtains.TouchEventInterceptor
import curtains.phoneWindow
import curtains.touchEventInterceptors
import java.lang.ref.WeakReference
import java.util.Date
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Owns only its own Curtains interceptors, independently of replay and session-activity tracking. */
internal class PostHogElementInteractionIntegration(
    private val config: PostHogAndroidConfig,
    private val mainHandler: MainHandler = MainHandler(),
    private val lifecycle: Lifecycle = ProcessLifecycleOwner.get().lifecycle,
) : PostHogIntegration, DefaultLifecycleObserver, PostHogCaptureContextReceiver {
    private companion object {
        // All ownership and window work runs on main, including deferred setup and close.
        var owner: PostHogElementInteractionIntegration? = null
    }

    private val lifecycleGeneration = AtomicLong()
    private val interceptors = WeakHashMap<Window, TouchEventInterceptor>()
    private var postHog: PostHogInterface? = null
    private val captureGuard = PostHogCaptureGuard()
    private val rage = InteractionRageDetector()
    private val dead =
        InteractionDeadDetector(config, mainHandler, ::generation, ::sessionId, { epoch, properties, timestamp ->
            (postHog as? PostHog)?.captureGuarded(captureGuard, epoch, "\$dead_click", properties, timestamp)
        })

    private fun sessionId(): String? = postHog?.getSessionId()?.toString()

    private fun generation(): Long? {
        val client = postHog as? PostHog ?: return null
        return if (client.isOptOut()) null else captureGuard.generation()
    }

    private val invalidationScheduled = AtomicBoolean()
    private val invalidateOnMain =
        Runnable {
            invalidationScheduled.set(false)
            safely { resetDetectors() }
        }

    override fun onCaptureContextChange(inProgress: Boolean) {
        captureGuard.onCaptureContextChange(inProgress)
        if (!inProgress) return
        // Core calls this on any thread. Coalesce signals; never wait for main or touch UI here.
        if (invalidationScheduled.compareAndSet(false, true)) mainHandler.handler.post(invalidateOnMain)
    }

    override fun onStop(owner: LifecycleOwner) {
        resetDetectors()
    }

    private fun resetDetectors() {
        rage.reset()
        dead.cancel()
    }

    private fun enabled(): Boolean = config.captureElementInteractions || config.captureRageClicks || config.captureDeadClicks

    private val rootsListener =
        OnRootViewsChangedListener { view, added ->
            onMain {
                if (owner !== this) return@onMain
                resetDetectors()
                val window = view.phoneWindow ?: return@onMain
                if (added) attach(view, window) else detach(window)
            }
        }

    override fun install(postHog: PostHogInterface) {
        if (!enabled()) return
        val generation = lifecycleGeneration.incrementAndGet()
        onMain {
            if (generation != lifecycleGeneration.get() || owner != null) return@onMain
            owner = this
            this.postHog = postHog
            captureGuard.setActive(true)
            try {
                lifecycle.addObserver(this)
                Curtains.onRootViewsChangedListeners += rootsListener
                Curtains.rootViews.forEach { view -> safely { view.phoneWindow?.let { attach(view, it) } } }
            } catch (failure: Throwable) {
                clearInstallation()
                throw failure
            }
        }
    }

    override fun uninstall() {
        val generation = lifecycleGeneration.incrementAndGet()
        onMain {
            if (generation != lifecycleGeneration.get() || owner !== this) return@onMain
            clearInstallation()
        }
    }

    private fun clearInstallation() {
        captureGuard.setActive(false)
        mainHandler.handler.removeCallbacks(invalidateOnMain)
        invalidationScheduled.set(false)
        resetDetectors()
        lifecycle.removeObserver(this)
        safely { Curtains.onRootViewsChangedListeners -= rootsListener }
        interceptors.keys.toList().forEach { window -> safely { detach(window) } }
        interceptors.clear()
        postHog = null
        owner = null
    }

    private fun attach(
        root: View,
        window: Window,
    ) {
        if (interceptors.containsKey(window)) return
        val weakRoot = WeakReference(root)
        val weakWindow = WeakReference(window)
        val tracker = InteractionTapTracker()
        val interceptor =
            TouchEventInterceptor { event, dispatch ->
                safely {
                    if (Looper.myLooper() != mainHandler.mainLooper) return@safely
                    val client = postHog
                    val view = weakRoot.get()
                    val currentWindow = weakWindow.get()
                    if (client == null || client.isOptOut() || view == null || currentWindow == null ||
                        !enabled() || currentWindow.attributes.flags and FLAG_SECURE != 0
                    ) {
                        resetDetectors()
                        tracker.reset()
                    } else {
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) dead.cancel()
                        val target = tracker.onTouch(view, event)
                        if (target != null) {
                            val properties = interactionPropertiesForTap(target, view, event)
                            if (!config.captureRageClicks) rage.reset()
                            val epoch = generation()
                            val session = sessionId()
                            if (epoch != null && session != null) {
                                val detectorProperties = properties + ("\$session_id" to session)
                                dead.begin(view, target, epoch, event.rawX, event.rawY, detectorProperties)
                                if (config.captureRageClicks &&
                                    rage.tap(
                                        view,
                                        target,
                                        epoch,
                                        android.os.SystemClock.uptimeMillis(),
                                        (properties.getValue("\$touch_x") as Number).toFloat(),
                                        (properties.getValue("\$touch_y") as Number).toFloat(),
                                        session,
                                    )
                                ) {
                                    (client as? PostHog)?.captureGuarded(
                                        captureGuard,
                                        epoch,
                                        "\$rageclick",
                                        detectorProperties,
                                        Date(config.dateProvider.currentTimeMillis()),
                                    )
                                }
                            } else {
                                resetDetectors()
                            }
                            if (config.captureElementInteractions) client.capture("\$autocapture", properties = properties)
                        } else if (event.actionMasked == MotionEvent.ACTION_UP ||
                            event.actionMasked == MotionEvent.ACTION_CANCEL || event.pointerCount != 1
                        ) {
                            resetDetectors()
                        }
                    }
                }
                // Never catch host exceptions or retry dispatch. Even an SDK failure dispatches once.
                var dispatched = false
                try {
                    dispatch(event).also {
                        dispatched = true
                        safely { dead.checkResponse() }
                    }
                } finally {
                    if (!dispatched) safely { resetDetectors() }
                }
            }
        interceptors[window] = interceptor
        window.touchEventInterceptors += interceptor
    }

    private fun detach(window: Window) {
        interceptors.remove(window)?.let { window.touchEventInterceptors -= it }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == mainHandler.mainLooper) safely(block) else mainHandler.handler.post { safely(block) }
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            try {
                resetDetectors()
            } catch (_: Throwable) {
            }
            // Do not log views, MotionEvents, or exception messages that could contain UI content.
            try {
                config.logger.log("Element interaction observation failed.")
            } catch (_: Throwable) {
            }
        }
    }
}
