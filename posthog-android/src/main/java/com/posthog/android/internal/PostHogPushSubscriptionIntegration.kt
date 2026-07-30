package com.posthog.android.internal

import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.posthog.PostHogIntegration
import com.posthog.PostHogInterface
import com.posthog.android.PostHogAndroidConfig

/**
 * Auto-registers this device's push token at startup so PostHog Workflows can deliver push
 * notifications to it.
 *
 * Firebase Messaging is a `compileOnly` dependency — the SDK never ships its own messaging
 * service and never hard-depends on Firebase. When Firebase is absent, [PushTokenFetcher] no-ops
 * with a debug log. Instant token refresh still requires the host app to forward Firebase's
 * `onNewToken` to [PostHogInterface.registerPushNotificationToken]; the startup fetch here only
 * covers tokens refreshed since the last launch.
 */
internal class PostHogPushSubscriptionIntegration(
    private val config: PostHogAndroidConfig,
    private val tokenFetcher: PushTokenFetcher = FirebasePushTokenFetcher(config),
) : PostHogIntegration {
    private var postHog: PostHogInterface? = null

    override fun install(postHog: PostHogInterface) {
        this.postHog = postHog
        tokenFetcher.fetchToken { token, appId ->
            this.postHog?.registerPushNotificationToken(token, appId)
        }
    }

    override fun uninstall() {
        postHog = null
    }
}

/**
 * Resolves this device's push token and app identifier. Kept behind an interface so the Firebase
 * dependency stays isolated to [FirebasePushTokenFetcher] and can be faked in tests.
 */
internal fun interface PushTokenFetcher {
    fun fetchToken(onToken: (token: String, appId: String) -> Unit)
}

private const val FIREBASE_MESSAGING_CLASS = "com.google.firebase.messaging.FirebaseMessaging"

/**
 * Fetches the FCM token and Firebase `project_id` (used as the push `app_id`).
 *
 * Presence is checked reflectively first, so an app without Firebase on the classpath sees a
 * single debug log and nothing else. All direct Firebase references are confined to
 * [fetchFirebaseToken], which only runs once the class is confirmed present.
 */
internal class FirebasePushTokenFetcher(
    private val config: PostHogAndroidConfig,
    // test seam: firebase-messaging is on the unit-test classpath, so the absent-classpath
    // path is exercised by probing for a class name that doesn't resolve
    private val messagingClassName: String = FIREBASE_MESSAGING_CLASS,
) : PushTokenFetcher {
    override fun fetchToken(onToken: (token: String, appId: String) -> Unit) {
        try {
            Class.forName(messagingClassName)
        } catch (e: Throwable) {
            config.logger.log("Firebase Messaging not found on the classpath, skipping push token registration.")
            return
        }
        fetchFirebaseToken(onToken)
    }

    // firebase-messaging 25.1.0 deprecates getToken in favor of FID-based registration;
    // PostHog delivers via FCM HTTP v1 registration tokens, so we stay on it until the
    // backend supports FIDs.
    @Suppress("DEPRECATION")
    private fun fetchFirebaseToken(onToken: (token: String, appId: String) -> Unit) {
        val projectId =
            try {
                FirebaseApp.getInstance().options.projectId
            } catch (e: IllegalStateException) {
                config.logger.log("Firebase is not initialized, skipping push token registration: $e.")
                return
            } catch (e: Throwable) {
                config.logger.log("Failed to read Firebase project id, skipping push token registration: $e.")
                return
            }

        if (projectId.isNullOrBlank()) {
            config.logger.log("Firebase project id is missing, skipping push token registration.")
            return
        }

        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    if (!token.isNullOrBlank()) {
                        onToken(token, projectId)
                    } else {
                        config.logger.log("Firebase returned a blank push token, skipping registration.")
                    }
                } else {
                    config.logger.log("Failed to fetch Firebase push token: ${task.exception}.")
                }
            }
        } catch (e: Throwable) {
            config.logger.log("Failed to request Firebase push token: $e.")
        }
    }
}
