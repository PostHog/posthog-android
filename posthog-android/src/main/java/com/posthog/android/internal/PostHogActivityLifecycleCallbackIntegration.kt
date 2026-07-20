package com.posthog.android.internal

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
import com.posthog.PostHogIntegration
import com.posthog.PostHogInterface
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
    private var postHog: PostHogInterface? = null

    @Volatile
    private var lastHandledPushMessageId: String? = null

    private companion object {
        @Volatile
        private var integrationInstalled = false

        private const val GOOGLE_MESSAGE_ID = "google.message_id"
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) {
        if (config.capturePushNotificationOpened) {
            capturePushNotificationOpenedIfNeeded(activity)
        }
        if (config.captureDeepLinks) {
            activity.intent?.let { intent ->
                val props = mutableMapOf<String, Any>()
                val data = intent.data
                try {
                    data?.let {
                        for (item in it.queryParameterNames) {
                            val param = it.getQueryParameter(item)
                            if (!param.isNullOrEmpty()) {
                                props[item] = param
                            }
                        }
                    }
                } catch (e: UnsupportedOperationException) {
                    config.logger.log("Deep link $data has invalid query param names.")
                } finally {
                    data?.let { props["url"] = it.toString() }
                    intent.getReferrerInfo(config).let { props.putAll(it) }

                    if (props.isNotEmpty()) {
                        postHog?.capture("Deep Link Opened", properties = props)
                    }
                }
            }
        }
    }

    /**
     * Captures `$push_notification_opened` for a cold-start tray tap, detected via the launch intent's
     * `google.message_id`. Title/body aren't in the tray intent (only the `posthog` JSON extra is);
     * warm-start `onNewIntent` and foreground data messages need the manual API. Dedupes on message id
     * so an Activity recreation doesn't double-capture.
     */
    private fun capturePushNotificationOpenedIfNeeded(activity: Activity) {
        val intent = activity.intent ?: return
        // Reading extras unmarshals the whole Bundle; a launch intent carrying a Serializable/Parcelable
        // extra whose class isn't on this app's classloader throws BadParcelableException here. This runs
        // inside the framework onActivityCreated callback, so an uncaught throw crashes the host app.
        try {
            val messageId = intent.getStringExtra(GOOGLE_MESSAGE_ID) ?: return

            if (messageId == lastHandledPushMessageId) {
                return
            }
            lastHandledPushMessageId = messageId

            postHog?.capturePushNotificationOpened(
                title = null,
                body = null,
                payload = intent.extras?.toMap(),
            )
        } catch (e: Throwable) {
            config.logger.log("Failed to capture push notification opened: $e.")
        }
    }

    private fun Bundle.toMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        for (key in keySet()) {
            @Suppress("DEPRECATION")
            map[key] = get(key)
        }
        return map
    }

    override fun onActivityStarted(activity: Activity) {
        if (config.captureScreenViews) {
            val screenName = activity.activityLabelOrName(config)

            if (!screenName.isNullOrEmpty()) {
                postHog?.screen(screenName)
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

    override fun install(postHog: PostHogInterface) {
        if (integrationInstalled) {
            return
        }
        integrationInstalled = true

        this.postHog = postHog
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun uninstall() {
        this.postHog = null
        integrationInstalled = false
        application.unregisterActivityLifecycleCallbacks(this)
    }
}
