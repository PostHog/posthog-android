package com.posthog.android.internal

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.Intent
import android.os.Bundle
import com.posthog.PostHogIntegration
import com.posthog.PostHogInterface
import com.posthog.PostHogVisibleForTesting
import com.posthog.android.PostHogAndroidConfig
import com.posthog.internal.PostHogPreferences.Companion.PUSH_OPENED_MESSAGE_IDS
import java.util.concurrent.atomic.AtomicBoolean

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
    private var ownsInstallation = false

    internal companion object {
        private val integrationInstalled = AtomicBoolean(false)

        private const val GOOGLE_MESSAGE_ID = "google.message_id"

        /** Only the launch intent is redelivered after a process death, so a warm tap must not
         * displace it. */
        private const val PUSH_ID_HISTORY = 5
        private const val PUSH_ID_SEPARATOR = "\n"

        private val pushDedupeLock = Any()

        /**
         * Process-wide on purpose: a tap must not be re-captured after a `close()`/`setup()` cycle,
         * and the static entry point has no integration instance to hang this on.
         */
        private var lastHandledPushMessageId: String? = null

        @PostHogVisibleForTesting
        internal fun resetPushDedupe() {
            synchronized(pushDedupeLock) { lastHandledPushMessageId = null }
        }

        /**
         * Captures `$push_notification_opened` for a tray tap carried on [intent], deduped by
         * `google.message_id`. Title/body aren't in the tray intent (only the `posthog` JSON extra is).
         *
         * [usePersistedDedupe] additionally remembers the id on disk, so the dedupe outlives the
         * process. Only callers with no restore signal need that: `onActivityCreated` has
         * `savedInstanceState`, which is strictly better because it separates a restore from a genuine
         * second tap of the same notification — a persisted id cannot tell those apart, and would drop
         * the real one. Keeping the write behind the same flag means only hosts that opt in ever
         * write this key.
         */
        internal fun capturePushNotificationOpened(
            intent: Intent,
            postHog: PostHogInterface?,
            config: PostHogAndroidConfig,
            usePersistedDedupe: Boolean = false,
        ) {
            // Reading extras unmarshals the whole Bundle; a launch intent carrying a
            // Serializable/Parcelable extra whose class isn't on this app's classloader throws
            // BadParcelableException here. An uncaught throw would surface in a framework callback or
            // in host code, either way crashing the app.
            try {
                val target = postHog ?: return
                // Marking an id the SDK will refuse to send would burn it for good, and an opt-in later
                // in the session could never recover it.
                if (target.isOptOut()) return

                val messageId = intent.getStringExtra(GOOGLE_MESSAGE_ID) ?: return

                // Check and mark under one lock: a second caller for the same id must not slip
                // through while this one is still delivering.
                val payload =
                    synchronized(pushDedupeLock) {
                        val persistedIds =
                            if (usePersistedDedupe) persistedPushIds(config) else emptyList()
                        if (messageId == lastHandledPushMessageId || messageId in persistedIds) {
                            lastHandledPushMessageId = messageId
                            // The automatic path marks memory only. Persisting on the way out of a hit
                            // keeps a later restore — where that path is gated by savedInstanceState —
                            // from capturing the same tap a second time.
                            if (usePersistedDedupe && messageId !in persistedIds) {
                                rememberPushId(config, messageId)
                            }
                            return
                        }
                        // Read the risky full Bundle first: if toMap() throws, the id stays unmarked so
                        // a later activity (e.g. a trampoline) with a clean Bundle can retry.
                        val extras = intent.extras?.toMap()
                        lastHandledPushMessageId = messageId
                        if (usePersistedDedupe) {
                            rememberPushId(config, messageId)
                        }
                        extras
                    }

                target.capturePushNotificationOpened(
                    title = null,
                    body = null,
                    payload = payload,
                )
            } catch (e: Throwable) {
                config.logger.log("Failed to capture push notification opened: $e.")
            }
        }

        private fun persistedPushIds(config: PostHogAndroidConfig): List<String> =
            (config.cachePreferences?.getValue(PUSH_OPENED_MESSAGE_IDS) as? String)
                ?.split(PUSH_ID_SEPARATOR)
                ?.filter { it.isNotEmpty() }
                ?: emptyList()

        private fun rememberPushId(
            config: PostHogAndroidConfig,
            messageId: String,
        ) {
            val ids = (listOf(messageId) + persistedPushIds(config)).distinct().take(PUSH_ID_HISTORY)
            config.cachePreferences?.setValue(PUSH_OPENED_MESSAGE_IDS, ids.joinToString(PUSH_ID_SEPARATOR))
        }

        private fun Bundle.toMap(): Map<String, Any?> {
            val map = mutableMapOf<String, Any?>()
            for (key in keySet()) {
                @Suppress("DEPRECATION")
                map[key] = get(key)
            }
            return map
        }
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) {
        // A non-null savedInstanceState redelivers the original launch intent (config-change recreation
        // or process-kill restore). The in-memory id guard resets with the process, so gate on a fresh launch.
        if (config.capturePushNotificationOpened && savedInstanceState == null) {
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

    private fun capturePushNotificationOpenedIfNeeded(activity: Activity) {
        val intent = activity.intent ?: return
        capturePushNotificationOpened(intent, postHog, config)
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

    @Synchronized
    override fun install(postHog: PostHogInterface) {
        if (!integrationInstalled.compareAndSet(false, true)) {
            return
        }
        ownsInstallation = true

        this.postHog = postHog
        application.registerActivityLifecycleCallbacks(this)
    }

    @Synchronized
    override fun uninstall() {
        if (!ownsInstallation) {
            return
        }
        try {
            this.postHog = null
            application.unregisterActivityLifecycleCallbacks(this)
        } finally {
            ownsInstallation = false
            integrationInstalled.set(false)
        }
    }
}
