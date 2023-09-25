package com.posthog.android.internal

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
import com.posthog.PostHog
import com.posthog.PostHogIntegration
import com.posthog.android.PostHogAndroidConfig

internal class PostHogActivityLifecycleCallback(private val application: Application, private val config: PostHogAndroidConfig) : ActivityLifecycleCallbacks, PostHogIntegration {
    // TODO: lifecycle events
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (config.captureDeepLinks) {
            activity.intent.data?.let {
                val props = mutableMapOf<String, Any>()
                for (item in it.queryParameterNames) {
                    val param = it.getQueryParameter(item)
                    if (!param.isNullOrEmpty()) {
                        props[item] = param
                    }
                }
                props["url"] = it.toString()
                PostHog.capture("Deep Link Opened", properties = props)
            }
        }
    }

    override fun onActivityStarted(activity: Activity) {
        if (config.captureRecordScreenViews) {
            val activityLabel = activity.activityLabel(config)
            activityLabel?.let {
                PostHog.screen(it)
            }
        }
    }

    override fun onActivityResumed(activity: Activity) {
    }

    override fun onActivityPaused(activity: Activity) {
    }

    override fun onActivityStopped(activity: Activity) {
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
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
