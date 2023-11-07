package com.posthog.android.internal

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
import com.posthog.PostHog
import com.posthog.PostHogIntegration
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.replay.internal.PostHogWindowRecorder
import java.util.UUID
import java.util.WeakHashMap

/**
 * Captures deep link and screen view events
 * @property application the App Context
 * @property config the Config
 */
internal class PostHogActivityLifecycleCallbackIntegration(
    private val application: Application,
    private val config: PostHogAndroidConfig,
) : ActivityLifecycleCallbacks, PostHogIntegration {

    private val activities = WeakHashMap<Activity, PostHogWindowRecorder>()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
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
            val activityLabel = activity.activityLabel(config)
            if (!activityLabel.isNullOrEmpty()) {
                PostHog.screen(activityLabel)
            }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (config.sessionReplay) {
            val recorder = activities[activity] ?: PostHogWindowRecorder(activity)

            activities[activity] = recorder
            recorder.startRecording()
        }
    }

    override fun onActivityPaused(activity: Activity) {
        stopRecording(activity, false)
    }

    private fun stopRecording(activity: Activity, removeActivity: Boolean = true) {
        if (config.sessionReplay) {
            val recorder = activities[activity]
            recorder?.let {
                it.pauseRecording()
                if (removeActivity) {
                    // in this case is a full stop since its removing the activity
                    activities.remove(activity)

                    val rrEvents = it.stopRecording()
                    val properties = mutableMapOf<String, Any>()
                    properties["\$snapshot_data"] = rrEvents
                    // TODO: implement session id and window id
                    properties["\$session_id"] = UUID.randomUUID().toString()
                    properties["\$window_id"] = UUID.randomUUID().toString()
                    properties["distinct_id"] = PostHog.distinctId()
                    // TODO: missing $snapshot_bytes
                    PostHog.capture("\$snapshot", properties = properties)
                }
            }
        }
    }

    override fun onActivityStopped(activity: Activity) {
        stopRecording(activity)
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
