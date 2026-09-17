package com.posthog.android.internal

import android.os.Looper
import android.view.View
import android.view.Window
import com.posthog.PostHogIntegration
import com.posthog.PostHogInterface
import com.posthog.android.PostHogAndroidConfig
import curtains.Curtains
import curtains.OnRootViewsChangedListener
import curtains.TouchEventInterceptor
import curtains.phoneWindow
import curtains.touchEventInterceptors
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

/** Owns only its own Curtains interceptors, independently of replay and session-activity tracking. */
internal class PostHogElementInteractionIntegration(
    private val config: PostHogAndroidConfig,
    private val mainHandler: MainHandler = MainHandler(),
) : PostHogIntegration {
    private companion object {
        // All ownership and window work runs on main, including deferred setup and close.
        var owner: PostHogElementInteractionIntegration? = null
    }

    private val lifecycleGeneration = AtomicLong()
    private val interceptors = WeakHashMap<Window, TouchEventInterceptor>()
    private var postHog: PostHogInterface? = null
    private val rootsListener =
        OnRootViewsChangedListener { view, added ->
            onMain {
                if (owner !== this) return@onMain
                val window = view.phoneWindow ?: return@onMain
                if (added) attach(view, window) else detach(window)
            }
        }

    override fun install(postHog: PostHogInterface) {
        if (!config.captureElementInteractions) return
        val generation = lifecycleGeneration.incrementAndGet()
        onMain {
            if (generation != lifecycleGeneration.get() || owner != null) return@onMain
            owner = this
            this.postHog = postHog
            try {
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
        val tracker = InteractionTapTracker()
        val interceptor =
            TouchEventInterceptor { event, dispatch ->
                safely {
                    if (Looper.myLooper() != mainHandler.mainLooper) return@safely
                    val client = postHog
                    val view = weakRoot.get()
                    if (client == null || client.isOptOut() || view == null || !config.captureElementInteractions) {
                        tracker.reset()
                    } else {
                        tracker.onTouch(view, event)?.let { target ->
                            client.capture("\$autocapture", properties = interactionPropertiesForTap(target, view, event))
                        }
                    }
                }
                // Never catch host exceptions or retry dispatch. Even an SDK failure dispatches once.
                dispatch(event)
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
            // Do not log views, MotionEvents, or exception messages that could contain UI content.
            try {
                config.logger.log("Element interaction observation failed.")
            } catch (_: Throwable) {
            }
        }
    }
}
