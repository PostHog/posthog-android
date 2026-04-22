package com.posthog.android

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.posthog.PostHog

/**
 * Wraps an [ActivityResultLauncher] for the POST_NOTIFICATIONS permission and ties
 * its result to FCM token registration with PostHog. Created by
 * [PostHogPushNotifications.registerPermissionLauncher]; safe to invoke [launch] from
 * any lifecycle state (e.g. button handlers) after the owning activity is created.
 */
public class PostHogPushPermissionLauncher internal constructor(
    private val activity: Activity,
    private val permissionLauncher: ActivityResultLauncher<String>?,
) {
    /**
     * If permission is already granted (or not required), fetches the FCM token and
     * registers it with PostHog immediately. Otherwise, prompts the user for
     * POST_NOTIFICATIONS; on grant, the token is fetched and registered.
     */
    public fun launch() {
        if (PostHogPushNotifications.hasPermission(activity)) {
            PostHogPushNotifications.fetchAndRegisterFcmToken(activity)
            return
        }
        permissionLauncher?.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/**
 * Utility class for handling push notification registration with PostHog.
 *
 * Requirements:
 * - Firebase Messaging must be included in the app's dependencies
 * - Firebase must be initialized in the app
 *
 * Usage:
 * ```kotlin
 * class MyActivity : ComponentActivity() {
 *     // Must be created before the activity reaches STARTED — a field initializer
 *     // or onCreate is fine. Do NOT call this from a button handler.
 *     private val pushLauncher = PostHogPushNotifications.registerPermissionLauncher(this)
 *
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *         // Invoke from anywhere — button click, settings toggle, first launch, etc.
 *         pushLauncher.launch()
 *     }
 * }
 * ```
 */
public object PostHogPushNotifications {
    /**
     * Registers an Activity Result launcher for the POST_NOTIFICATIONS permission.
     * Must be called before the activity reaches STARTED (field initializer or onCreate).
     * The returned [PostHogPushPermissionLauncher] can be invoked from any lifecycle
     * state to prompt for permission (if needed) and register the FCM token.
     *
     * @param activity the ComponentActivity that owns the launcher
     * @param onPermissionResult optional callback; receives `true` if permission was
     *   granted (or not required), `false` if denied
     */
    @JvmStatic
    @JvmOverloads
    public fun registerPermissionLauncher(
        activity: ComponentActivity,
        onPermissionResult: ((Boolean) -> Unit)? = null,
    ): PostHogPushPermissionLauncher {
        val launcher =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.registerForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) {
                        fetchAndRegisterFcmToken(activity)
                    }
                    onPermissionResult?.invoke(granted)
                }
            } else {
                null
            }
        return PostHogPushPermissionLauncher(activity, launcher)
    }

    /**
     * Checks whether push notification permission has already been granted.
     *
     * @param activity the Activity to check permission against
     * @return true if permission is granted or the device is below Android 13
     */
    @JvmStatic
    public fun hasPermission(activity: Activity): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Registers a push notification token directly with PostHog without requesting permission.
     * Use this if you already have the FCM token (e.g. from FirebaseMessagingService.onNewToken).
     *
     * @param deviceToken the FCM device token
     * @param firebaseProjectId the Firebase project ID (used as appId for the push subscription)
     */
    @JvmStatic
    public fun registerToken(
        deviceToken: String,
        firebaseProjectId: String,
    ) {
        PostHog.registerPushNotificationToken(
            deviceToken = deviceToken,
            appId = firebaseProjectId,
            platform = "android",
        )
    }

    internal fun fetchAndRegisterFcmToken(activity: Activity) {
        try {
            val firebaseMessaging =
                Class.forName("com.google.firebase.messaging.FirebaseMessaging")

            val getInstance = firebaseMessaging.getMethod("getInstance")
            val instance = getInstance.invoke(null)

            val getToken = firebaseMessaging.getMethod("getToken")
            val task = getToken.invoke(instance)

            val firebaseApp = Class.forName("com.google.firebase.FirebaseApp")
            val getFirebaseInstance = firebaseApp.getMethod("getInstance")
            val appInstance = getFirebaseInstance.invoke(null)
            val getOptions = firebaseApp.getMethod("getOptions")
            val options = getOptions.invoke(appInstance)

            val firebaseOptions = Class.forName("com.google.firebase.FirebaseOptions")
            val getProjectId = firebaseOptions.getMethod("getProjectId")
            val projectId = getProjectId.invoke(options) as? String ?: ""

            val taskClass = Class.forName("com.google.android.gms.tasks.Task")
            val addOnSuccessListenerMethod =
                taskClass.getMethod(
                    "addOnSuccessListener",
                    Class.forName("com.google.android.gms.tasks.OnSuccessListener"),
                )

            val proxy =
                java.lang.reflect.Proxy.newProxyInstance(
                    activity.classLoader,
                    arrayOf(Class.forName("com.google.android.gms.tasks.OnSuccessListener")),
                ) { _, _, args ->
                    val token = args?.firstOrNull() as? String
                    if (!token.isNullOrBlank() && projectId.isNotBlank()) {
                        PostHog.registerPushNotificationToken(
                            deviceToken = token,
                            appId = projectId,
                            platform = "android",
                        )
                    }
                    null
                }

            addOnSuccessListenerMethod.invoke(task, proxy)
        } catch (e: ClassNotFoundException) {
            val config = PostHog.getConfig<PostHogAndroidConfig>()
            config?.logger?.log(
                "Firebase Messaging is not available. Add firebase-messaging dependency to use push notifications.",
            )
        } catch (e: Throwable) {
            val config = PostHog.getConfig<PostHogAndroidConfig>()
            config?.logger?.log("Failed to fetch FCM token: $e.")
        }
    }
}
