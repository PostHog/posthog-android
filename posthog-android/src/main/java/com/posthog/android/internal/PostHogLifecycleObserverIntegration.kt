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
        // Foregrounding counts as activity so an idle session rotates here, not on the
        // first capture after foregrounding.
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
            // Resume replay if it was active when the previous onStop tore it down for a
            // 24h rotation; otherwise the new session would have no recording.
            if (replayActiveBeforeRotation.compareAndSet(true, false)) {
                postHog?.startSessionReplay(resumeCurrent = true)
            }
        } else if (PostHogSessionManager.isSessionExceedingMaxDuration(currentTimeMillis)) {
            // endSession + startSession both fire onSessionIdChangedListener on the manager;
            // the replay integration handles stop + sampling-aware restart from there.
            postHog?.endSession()
            postHog?.startSession()
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
            timer.schedule(timerTask, sessionMaxInterval)
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
        if (config.captureApplicationLifecycleEvents) {
            postHog?.capture("Application Backgrounded")
        }
        postHog?.flush()

        if (wasExpired) {
            cancelTask()
            val wasReplayActive = postHog?.isSessionReplayActive() == true
            postHog?.endSession()
            // Synchronous stop guarantees replay is torn down before the process suspends;
            // the listener-driven path posts to main and may not run in time.
            postHog?.stopSessionReplay()
            replayActiveBeforeRotation.set(wasReplayActive)
            // Zeroing forces the next onStart into the "create a fresh session" branch.
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
