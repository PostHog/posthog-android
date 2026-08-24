package com.posthog.android.internal

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.posthog.PostHogIntegration
import com.posthog.PostHogInterface
import com.posthog.android.PostHogAndroidConfig
import com.posthog.internal.PostHogSessionManager
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures app opened and backgrounded events
 * @property context the App Context
 * @property config the Config
 * @property lifecycle The Lifecycle, defaults to ProcessLifecycleOwner.get().lifecycle
 */
internal class PostHogLifecycleObserverIntegration(
    private val context: Context,
    private val config: PostHogAndroidConfig,
    private val mainHandler: MainHandler,
    private val lifecycle: Lifecycle = ProcessLifecycleOwner.get().lifecycle,
) : DefaultLifecycleObserver, PostHogIntegration {
    private val timerLock = Any()
    private var timer = Timer(true)
    private var timerTask: TimerTask? = null

    // Bg timeout for forcing endSession when no events fire to drive the manager's getter check.
    private val bgEndSessionDelayMs = (1000 * 60 * 30).toLong() // 30 minutes

    private var postHog: PostHogInterface? = null
    private var ownsInstallation = false

    private companion object {
        // in case there are multiple instances or the SDK is closed/setup again
        // the value is still cached
        @JvmStatic
        @Volatile
        private var fromBackground = false

        // Whether the app has been backgrounded at least once, tracked independently of
        // captureApplicationLifecycleEvents. Gates the onStart flag reload so the initial cold
        // start does not fire a second /flags request on top of the one SDK setup already sends.
        @JvmStatic
        @Volatile
        private var hasBackgrounded = false

        private val integrationInstalled = AtomicBoolean(false)
    }

    override fun onStart(owner: LifecycleOwner) {
        cancelTask()
        PostHogSessionManager.setAppInBackground(false)

        // Refresh the flags before the session rotation below re-checks the session-replay flag.
        // Without it, a flag that turned on while the app was backgrounded (a wider rollout, or
        // person-property targeting the server now resolves) stays false until the next cold start
        // or identify(), so flag-gated replay never starts for a returning user. The reload
        // re-evaluates the linked flag when the response lands and resumes replay through the replay
        // integration's remote-config callback. Gated on preloadFeatureFlags so an app that opts out
        // of automatic flag loading keeps that behaviour, and on hasBackgrounded so the initial cold
        // start does not duplicate the /flags request SDK setup already sends.
        if (config.preloadFeatureFlags && hasBackgrounded) {
            postHog?.reloadFeatureFlags()
        }

        // touchSession rotates an idle session; startSession creates a fresh one if the
        // session was cleared during bg. Both fire the manager's session-id-changed listener,
        // which drives the sampling-aware replay restart in the replay integration.
        PostHogSessionManager.touchSession()
        postHog?.startSession()

        if (config.captureApplicationLifecycleEvents) {
            val props = mutableMapOf<String, Any>()
            props["from_background"] = fromBackground

            if (!fromBackground) {
                getPackageInfo(context, config)?.let { packageInfo ->
                    packageInfo.versionName?.let { props["version"] = it }
                    packageInfo.versionCodeCompat()?.let { props["build"] = it }
                }

                fromBackground = true
            }

            postHog?.capture("Application Opened", properties = props)
        }
    }

    private fun cancelTask() {
        synchronized(timerLock) {
            timerTask?.cancel()
            timerTask = null
        }
    }

    private fun scheduleEndSession() {
        // Backgrounded apps may fire no events, so the getter's idle check never runs;
        // this timer guarantees the session ends after 30 min of bg per the
        // PostHogInterface.endSession docstring.
        synchronized(timerLock) {
            cancelTask()
            timerTask =
                object : TimerTask() {
                    override fun run() {
                        postHog?.endSession()
                    }
                }
            timer.schedule(timerTask, bgEndSessionDelayMs)
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        val currentTimeMillis = config.dateProvider.currentTimeMillis()
        // Capture expiry before flipping the bg flag: once bg=true, getActiveSessionId
        // would clear an expired session and zero sessionStartedAt, hiding it from the
        // wasExpired branch below.
        val wasExpired = PostHogSessionManager.isSessionExceedingMaxDuration(currentTimeMillis)

        // Touch while still foregrounded so the activity timestamp moves forward; doing
        // this after setAppInBackground(true) would no-op.
        PostHogSessionManager.touchSession()
        PostHogSessionManager.setAppInBackground(true)
        // Record the background transition so the next onStart reloads the flags. The cold-start
        // onStart must not reload, since SDK setup already fetched the flags on process start.
        hasBackgrounded = true
        if (config.captureApplicationLifecycleEvents) {
            postHog?.capture("Application Backgrounded")
        }
        postHog?.flush()

        if (wasExpired) {
            cancelTask()
            // Force the rotation now and stop replay synchronously — process may suspend
            // before the listener's main-thread post can run.
            postHog?.endSession()
            postHog?.stopSessionReplay()
        } else {
            scheduleEndSession()
        }
    }

    private fun add() {
        lifecycle.addObserver(this)
    }

    @Synchronized
    override fun install(postHog: PostHogInterface) {
        if (!integrationInstalled.compareAndSet(false, true)) {
            return
        }
        ownsInstallation = true

        try {
            this.postHog = postHog
            if (isMainThread(mainHandler)) {
                add()
            } else {
                mainHandler.handler.post {
                    add()
                }
            }
        } catch (e: Throwable) {
            config.logger.log("Failed to install PostHogLifecycleObserverIntegration: $e")
        }
    }

    private fun remove() {
        lifecycle.removeObserver(this)
    }

    @Synchronized
    override fun uninstall() {
        if (!ownsInstallation) {
            return
        }
        try {
            this.postHog = null
            if (isMainThread(mainHandler)) {
                remove()
            } else {
                mainHandler.handler.post {
                    remove()
                }
            }
        } catch (e: Throwable) {
            config.logger.log("Failed to uninstall PostHogLifecycleObserverIntegration: $e")
        } finally {
            ownsInstallation = false
            integrationInstalled.set(false)
        }
    }
}
