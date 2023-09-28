package com.posthog.android.internal

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.posthog.PostHog
import com.posthog.PostHogIntegration
import com.posthog.android.PostHogAndroidConfig

/**
 * Captures app opened and backgrounded events
 * @property context the App Context
 * @property config the Config
 */
internal class PostHogLifecycleObserverIntegration(
    private val context: Context,
    private val config: PostHogAndroidConfig,
) : DefaultLifecycleObserver, PostHogIntegration {
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        // in case there are multiple instances or the SDK is closed/setup again
        // the value is still cached
        @JvmStatic
        @Volatile
        private var fromBackground = false
    }

    override fun onStart(owner: LifecycleOwner) {
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

    override fun onStop(owner: LifecycleOwner) {
        PostHog.capture("Application Backgrounded")
    }

    private fun add() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
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
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
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
