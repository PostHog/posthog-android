package com.posthog.android.internal

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
import com.posthog.PostHog
import com.posthog.PostHogIntegration
import com.posthog.android.PostHogAndroidConfig

/**
 * Captures deep link and screen view events
 * @property application the App Context
 * @property config the Config
 */
internal class PostHogActivityLifecycleCallbackIntegration(
    private val application: Application,
    private val config: PostHogAndroidConfig,
) : ActivityLifecycleCallbacks, PostHogIntegration {
    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) {
        if (config.captureDeepLinks) {
            activity.intent.data?.let {
                val props = mutableMapOf<String, Any>()
                try {
                    for (item in it.queryParameterNames) {
                        val param = it.getQueryParameter(item)
                        if (!param.isNullOrEmpty()) {
                            props[item] = param
                        }
                    }
                } catch (e: UnsupportedOperationException) {
                    config.logger.log("Deep link $it has invalid query param names.")
                } finally {
                    props["url"] = it.toString()
                    PostHog.capture("Deep Link Opened", properties = props)
                }
            }
        }
    }

    override fun onActivityStarted(activity: Activity) {
        if (config.captureScreenViews) {
            val screenName = activity.activityLabelOrName(config)

            if (!screenName.isNullOrEmpty()) {
                PostHog.screen(screenName)
            }
        }
    }


    override fun onActivityResumed(activity: Activity) {
    }

    override fun onActivityPaused(activity: Activity) {
    }

    override fun onActivityStopped(activity: Activity) {
    }

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) {
    }

    override fun onActivityDestroyed(activity: Activity) {
    }

    override fun install() {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun uninstall() {
        application.unregisterActivityLifecycleCallbacks(this)
    }
}
