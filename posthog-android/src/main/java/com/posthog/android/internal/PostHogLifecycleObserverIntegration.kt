package com.posthog.android.internal

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.posthog.PostHog
import com.posthog.PostHogIntegration
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.replay.PostHogReplayIntegration
import java.util.Timer
import java.util.TimerTask
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
    private val replayIntegration: PostHogReplayIntegration,
    private val lifecycle: Lifecycle = ProcessLifecycleOwner.get().lifecycle,
) : DefaultLifecycleObserver, PostHogIntegration {
    private val handler = Handler(Looper.getMainLooper())
    private val timerLock = Any()
    private var timer = Timer(true)
    private var timerTask: TimerTask? = null
    private val lastUpdatedSession = AtomicLong(0L)
    private val sessionMaxInterval = 30_000L // 30 seconds

    companion object {
        // in case there are multiple instances or the SDK is closed/setup again
        // the value is still cached
        @JvmStatic
        @Volatile
        private var fromBackground = false
    }

    override fun onStart(owner: LifecycleOwner) {
        startSession()

        if (config.captureApplicationLifecycleEvents) {
            val props = mutableMapOf<String, Any>()
            props["from_background"] = fromBackground

            if (!fromBackground) {
                getPackageInfo(context, config)?.let { packageInfo ->
                    props["version"] = packageInfo.versionName
                    props["build"] = packageInfo.versionCodeCompat()
                }

                fromBackground = true
            }

            PostHog.capture("Application Opened", properties = props)
        }
    }

    private fun startSession() {
        cancelTask()

        val currentTimeMillis = System.currentTimeMillis()
        val lastUpdatedSession = lastUpdatedSession.get()

        if (lastUpdatedSession == 0L ||
            (lastUpdatedSession + sessionMaxInterval) <= currentTimeMillis
        ) {
            PostHog.startSession()
            replayIntegration.sessionActive(true)
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
        synchronized(timerLock) {
            cancelTask()
            timerTask = object : TimerTask() {
                override fun run() {
                    PostHog.endSession()
                    replayIntegration.sessionActive(false)
                }
            }
            timer.schedule(timerTask, sessionMaxInterval)
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        if (config.captureApplicationLifecycleEvents) {
            PostHog.capture("Application Backgrounded")
        }

        val currentTimeMillis = System.currentTimeMillis()
        lastUpdatedSession.set(currentTimeMillis)
        scheduleEndSession()
    }

    private fun add() {
        lifecycle.addObserver(this)
    }

    override fun install() {
        try {
            if (isMainThread()) {
                add()
            } else {
                handler.post {
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
            if (isMainThread()) {
                remove()
            } else {
                handler.post {
                    remove()
                }
            }
        } catch (e: Throwable) {
            config.logger.log("Failed to uninstall PostHogLifecycleObserverIntegration: $e")
        }
    }
}
