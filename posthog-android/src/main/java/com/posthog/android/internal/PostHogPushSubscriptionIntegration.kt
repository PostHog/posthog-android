package com.posthog.android.internal

import android.content.Context
import com.posthog.PostHogIntegration
import com.posthog.PostHogInterface
import com.posthog.PostHogOptInReceiver
import com.posthog.android.PostHogAndroidConfig
import com.posthog.internal.PostHogPushSubscriptionReceiver
import com.posthog.internal.PostHogThreadFactory
import com.posthog.internal.executeSafely
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Auto-registers this device's push token at startup so PostHog Workflows can deliver push
 * notifications to it.
 *
 * Firebase Messaging is accessed reflectively — the SDK never ships its own messaging
 * service and never hard-depends on Firebase. When Firebase is absent, [PushTokenFetcher] no-ops
 * with a debug log. Instant token refresh still requires the host app to forward Firebase's
 * `onNewToken` (or, in Firebase Installation ID mode, `onRegistered`) to
 * [PostHogInterface.registerPushNotificationToken]; the startup fetch here only covers tokens
 * refreshed since the last launch.
 */
internal class PostHogPushSubscriptionIntegration(
    private val config: PostHogAndroidConfig,
    private val tokenFetcher: PushTokenFetcher = FirebasePushTokenFetcher(config),
    // Mirrors PostHogReplayIntegration's injectable executor: install() runs on setup()'s caller
    // thread (typically main, inside Application.onCreate()), and FirebaseMessaging's first-touch
    // init is synchronous, so the fetch is hopped off-thread. Tests inject a synchronous executor.
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(PostHogThreadFactory("PostHogPushSub")),
) : PostHogIntegration, PostHogOptInReceiver {
    private var postHog: PostHogInterface? = null
    private var ownsInstallation = false

    private companion object {
        private val integrationInstalled = AtomicBoolean(false)
    }

    @Synchronized
    override fun install(postHog: PostHogInterface) {
        if (!integrationInstalled.compareAndSet(false, true)) {
            return
        }
        ownsInstallation = true
        this.postHog = postHog
        fetchAndRegister()
    }

    // Opt-in re-arms push: a prior logout unregister cleared the token, so refetch it and re-register
    // the device instead of leaving it unsubscribed until the next app launch (posthog-android#675).
    override fun onOptIn() {
        fetchAndRegister()
    }

    private fun fetchAndRegister() {
        executor.executeSafely {
            tokenFetcher.fetchToken { token, appId ->
                val postHog = this.postHog
                if (postHog is PostHogPushSubscriptionReceiver) {
                    postHog.registerPushNotificationTokenIfNeeded(token, appId)
                } else {
                    postHog?.registerPushNotificationToken(token, appId)
                }
            }
        }
    }

    @Synchronized
    override fun uninstall() {
        if (!ownsInstallation) {
            return
        }
        try {
            postHog = null
        } finally {
            ownsInstallation = false
            integrationInstalled.set(false)
        }
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

// The manifest flag firebase-messaging reads to switch to Firebase Installation ID registration.
private const val INSTALLATION_ID_ENABLED_KEY = "firebase_messaging_installation_id_enabled"

/**
 * Fetches the FCM token, or the Firebase Installation ID when the app opted into FID registration,
 * and the Firebase `project_id` (used as the push `app_id`).
 *
 * All Firebase and Play Services access must stay reflective: even unused bytecode type references
 * cause F-Droid's scanner to reject apps that do not include Firebase.
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

    private fun fetchFirebaseToken(onToken: (token: String, appId: String) -> Unit) {
        try {
            val appClass = Class.forName("com.google.firebase.FirebaseApp")
            val firebaseApp =
                try {
                    appClass.getMethod("getInstance").invoke(null)!!
                } catch (e: InvocationTargetException) {
                    if (e.targetException is IllegalStateException) {
                        config.logger.log("Firebase is not initialized, skipping push token registration: ${e.targetException}.")
                        return
                    }
                    throw e
                }
            val options = appClass.getMethod("getOptions").invoke(firebaseApp)
            val projectId =
                Class.forName("com.google.firebase.FirebaseOptions")
                    .getMethod("getProjectId").invoke(options) as? String

            if (projectId.isNullOrBlank()) {
                config.logger.log("Firebase project id is missing, skipping push token registration.")
                return
            }

            // The two modes are exclusive: with the flag on, getToken() fails and FCM only accepts the
            // FID as a send target.
            val installationIdEnabled = isInstallationIdEnabled(firebaseApp, appClass)
            val task =
                if (installationIdEnabled) {
                    val installationsClass = Class.forName("com.google.firebase.installations.FirebaseInstallations")
                    val installations = installationsClass.getMethod("getInstance", appClass).invoke(null, firebaseApp)
                    installationsClass.getMethod("getId").invoke(installations)
                } else {
                    val messagingClass = Class.forName(messagingClassName)
                    val messaging = messagingClass.getMethod("getInstance").invoke(null)
                    messagingClass.getMethod("getToken").invoke(messaging)
                }
            handleCompletion(task, if (installationIdEnabled) "Firebase Installation ID" else "push token", projectId, onToken)
        } catch (e: Throwable) {
            val cause = (e as? InvocationTargetException)?.targetException ?: e
            config.logger.log("Failed to request Firebase push token: $cause.")
        }
    }

    private fun handleCompletion(
        task: Any?,
        label: String,
        projectId: String,
        onToken: (token: String, appId: String) -> Unit,
    ) {
        // Task implementations can be package-private or obfuscated; reflect on the public API.
        val taskClass = Class.forName("com.google.android.gms.tasks.Task")
        val listenerClass = Class.forName("com.google.android.gms.tasks.OnCompleteListener")
        val listener =
            Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { proxy, method, args ->
                when (method.name) {
                    "onComplete" -> {
                        // Completion runs asynchronously, outside fetchFirebaseToken's try/catch.
                        try {
                            val completedTask = args!![0]
                            if (taskClass.getMethod("isSuccessful").invoke(completedTask) as Boolean) {
                                val token = taskClass.getMethod("getResult").invoke(completedTask) as? String
                                if (!token.isNullOrBlank()) {
                                    onToken(token, projectId)
                                } else {
                                    config.logger.log("Firebase returned a blank $label, skipping registration.")
                                }
                            } else {
                                val exception = taskClass.getMethod("getException").invoke(completedTask)
                                config.logger.log("Failed to fetch Firebase $label: $exception.")
                            }
                        } catch (e: Throwable) {
                            config.logger.log("Failed to handle Firebase $label completion: $e.")
                        }
                        null
                    }
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "PostHogPushTokenListener"
                    else -> null
                }
            }
        taskClass.getMethod("addOnCompleteListener", listenerClass).invoke(task, listener)
    }

    private fun isInstallationIdEnabled(
        firebaseApp: Any,
        appClass: Class<*>,
    ): Boolean =
        try {
            val context = appClass.getMethod("getApplicationContext").invoke(firebaseApp) as Context
            getApplicationInfo(context).metaData?.getBoolean(INSTALLATION_ID_ENABLED_KEY, false) == true
        } catch (e: Throwable) {
            config.logger.log("Failed to read $INSTALLATION_ID_ENABLED_KEY, assuming FCM token registration: $e.")
            false
        }
}
