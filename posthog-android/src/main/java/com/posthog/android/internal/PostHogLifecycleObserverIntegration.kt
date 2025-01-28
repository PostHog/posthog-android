package com.posthog.android.internal

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.posthog.PostHogIntegration
import com.posthog.PostHogInterface
import com.posthog.android.PostHogAndroidConfig
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
    private val mainHandler: MainHandler,
    private val lifecycle: Lifecycle = ProcessLifecycleOwner.get().lifecycle,
) : DefaultLifecycleObserver, PostHogIntegration {
    private val timerLock = Any()
    private var timer = Timer(true)
    private var timerTask: TimerTask? = null
    private val lastUpdatedSession = AtomicLong(0L)
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
        if (config.captureApplicationLifecycleEvents) {
            postHog?.capture("Application Backgrounded")
        }

        val currentTimeMillis = config.dateProvider.currentTimeMillis()
        lastUpdatedSession.set(currentTimeMillis)
        scheduleEndSession()
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
