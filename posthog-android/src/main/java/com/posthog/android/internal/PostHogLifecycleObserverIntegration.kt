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
import java.util.concurrent.atomic.AtomicLong

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
    private val lastUpdatedSession = AtomicLong(0L)
    private val replayActiveBeforeRotation = AtomicBoolean(false)
    private val sessionMaxInterval = (1000 * 60 * 30).toLong() // 30 minutes

    private var postHog: PostHogInterface? = null

    private companion object {
        // in case there are multiple instances or the SDK is closed/setup again
        // the value is still cached
        @JvmStatic
        @Volatile
        private var fromBackground = false

        @Volatile
        private var integrationInstalled = false
    }

    override fun onStart(owner: LifecycleOwner) {
        PostHogSessionManager.setAppInBackground(false)
        // Foregrounding counts as activity (mirror iOS onDidBecomeActive).
        PostHogSessionManager.touchSession()
        startSession()

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

    private fun startSession() {
        cancelTask()

        val currentTimeMillis = config.dateProvider.currentTimeMillis()
        val lastUpdatedSession = lastUpdatedSession.get()

        if (lastUpdatedSession == 0L ||
            (lastUpdatedSession + sessionMaxInterval) <= currentTimeMillis
        ) {
            postHog?.startSession()
            // If the previous session was ended via 24h rotation in onStop,
            // restart replay so it continues under the new session
            if (replayActiveBeforeRotation.compareAndSet(true, false)) {
                postHog?.startSessionReplay(resumeCurrent = true)
            }
        } else if (PostHogSessionManager.isSessionExceedingMaxDuration(currentTimeMillis)) {
            // Session has been active for longer than 24 hours, rotate to a new session
            if (postHog?.isSessionReplayActive() == true) {
                postHog?.stopSessionReplay()

                // startSessionReplay will rotate the session id internally
                postHog?.startSessionReplay(resumeCurrent = false)
            } else {
                postHog?.endSession()
                postHog?.startSession()
            }
        }
        this.lastUpdatedSession.set(currentTimeMillis)
    }

    private fun cancelTask() {
        synchronized(timerLock) {
            timerTask?.cancel()
            timerTask = null
        }
    }

    private fun scheduleEndSession() {
        // This timer honors the PostHogInterface.endSession docstring promise:
        // "On Android, the SDK will automatically end a session when the app is
        // in the background for at least 30 minutes." The getter's inactivity
        // check isn't sufficient on its own because isSessionActive() reads the
        // field directly — without this timer, a backgrounded app that fires no
        // events would keep reporting an active session forever.
        synchronized(timerLock) {
            cancelTask()
            timerTask =
                object : TimerTask() {
                    override fun run() {
                        postHog?.endSession()
                    }
                }
            timer.schedule(timerTask, sessionMaxInterval)
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        val currentTimeMillis = config.dateProvider.currentTimeMillis()
        // Snapshot before flipping the bg flag: once we set it, the next getActiveSessionId
        // (e.g., while capturing "Application Backgrounded") may clear an expired session,
        // zeroing sessionStartedAt so the 24h check below would miss it.
        val wasExpired = PostHogSessionManager.isSessionExceedingMaxDuration(currentTimeMillis)

        PostHogSessionManager.setAppInBackground(true)
        if (config.captureApplicationLifecycleEvents) {
            postHog?.capture("Application Backgrounded")
        }
        postHog?.flush()

        // Session has been active for longer than 24 hours, rotate to a new session
        if (wasExpired) {
            cancelTask()
            val wasReplayActive = postHog?.isSessionReplayActive() == true
            postHog?.endSession()
            postHog?.stopSessionReplay()
            replayActiveBeforeRotation.set(wasReplayActive)
            // Reset so the next onStart knows to create a fresh session
            lastUpdatedSession.set(0L)
        } else {
            lastUpdatedSession.set(currentTimeMillis)
            scheduleEndSession()
        }
    }

    private fun add() {
        lifecycle.addObserver(this)
    }

    override fun install(postHog: PostHogInterface) {
        if (integrationInstalled) {
            return
        }
        integrationInstalled = true

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

    override fun uninstall() {
        try {
            integrationInstalled = false
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
        }
    }
}
