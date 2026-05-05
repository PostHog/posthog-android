package com.posthog.android.internal

import android.os.Build
import com.posthog.PostHogIntegration
import com.posthog.PostHogInterface
import com.posthog.android.PostHogAndroidConfig
import com.posthog.internal.PostHogSessionManager
import curtains.Curtains
import curtains.OnRootViewsChangedListener
import curtains.TouchEventInterceptor
import curtains.phoneWindow
import curtains.touchEventInterceptors

/**
 * Marks user touches as session activity by calling [PostHogSessionManager.touchSession]
 * on every dispatched MotionEvent.
 *
 * Decoupled from session replay so apps with replay disabled or sampled out still get
 * touch-driven inactivity rotation; otherwise session-id rotation behaviour would depend
 * on whether the user happened to be sampled, which session metrics rely on being stable.
 */
internal class PostHogTouchActivityIntegration(
    private val config: PostHogAndroidConfig,
) : PostHogIntegration {
    private companion object {
        @Volatile
        private var integrationInstalled = false
    }

    private val touchInterceptor =
        TouchEventInterceptor { motionEvent, dispatch ->
            try {
                PostHogSessionManager.touchSession()
            } catch (e: Throwable) {
                config.logger.log("PostHogTouchActivityIntegration touchSession failed: $e.")
            }
            dispatch(motionEvent)
        }

    private val onRootViewsChangedListener =
        OnRootViewsChangedListener { view, added ->
            try {
                val window = view.phoneWindow ?: return@OnRootViewsChangedListener
                if (added) {
                    if (touchInterceptor !in window.touchEventInterceptors) {
                        window.touchEventInterceptors += touchInterceptor
                    }
                } else {
                    window.touchEventInterceptors -= touchInterceptor
                }
            } catch (e: Throwable) {
                config.logger.log("PostHogTouchActivityIntegration root view changed failed: $e.")
            }
        }

    override fun install(postHog: PostHogInterface) {
        if (integrationInstalled || !isSupported()) {
            return
        }
        integrationInstalled = true
        try {
            Curtains.rootViews.forEach { view ->
                view.phoneWindow?.let { window ->
                    if (touchInterceptor !in window.touchEventInterceptors) {
                        window.touchEventInterceptors += touchInterceptor
                    }
                }
            }
            Curtains.onRootViewsChangedListeners += onRootViewsChangedListener
        } catch (e: Throwable) {
            config.logger.log("PostHogTouchActivityIntegration install failed: $e.")
        }
    }

    override fun uninstall() {
        try {
            Curtains.onRootViewsChangedListeners -= onRootViewsChangedListener
            Curtains.rootViews.forEach { view ->
                view.phoneWindow?.let { window ->
                    window.touchEventInterceptors -= touchInterceptor
                }
            }
        } catch (e: Throwable) {
            config.logger.log("PostHogTouchActivityIntegration uninstall failed: $e.")
        } finally {
            integrationInstalled = false
        }
    }

    private fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
}
