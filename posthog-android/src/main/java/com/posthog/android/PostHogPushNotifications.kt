package com.posthog.android

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.posthog.PostHog

/**
 * Utility class for handling push notification registration with PostHog.
 *
 * This class provides methods to request push notification permission and
 * automatically register the device's FCM token with PostHog when permission is granted.
 *
 * Requirements:
 * - Firebase Messaging must be included in the app's dependencies
 * - Firebase must be initialized in the app
 *
 * Usage:
 * ```kotlin
 * // Call from a ComponentActivity (AppCompatActivity, FragmentActivity, etc.)
 * PostHogPushNotifications.requestPermissionAndRegister(activity)
 * ```
 */
public object PostHogPushNotifications {
    /**
     * Requests push notification permission (on Android 13+) and automatically
     * registers the FCM device token with PostHog when permission is granted.
     *
     * On Android 12 and below, notifications are allowed by default, so this method
     * will directly proceed to register the FCM token.
     *
     * @param activity the ComponentActivity to use for the permission request.
     *   Must be a ComponentActivity (e.g. AppCompatActivity) to use the Activity Result API.
     * @param onPermissionResult optional callback that receives the permission result.
     *   `true` if permission was granted (or not needed), `false` if denied.
     */
    @JvmStatic
    @JvmOverloads
    public fun requestPermissionAndRegister(
        activity: ComponentActivity,
        onPermissionResult: ((Boolean) -> Unit)? = null,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission =
                ContextCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                fetchAndRegisterToken(activity)
                onPermissionResult?.invoke(true)
                return
            }

            val launcher =
                activity.registerForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) {
                        fetchAndRegisterToken(activity)
                    }
                    onPermissionResult?.invoke(granted)
                }

            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Below Android 13, notification permission is granted at install time
            fetchAndRegisterToken(activity)
            onPermissionResult?.invoke(true)
        }
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

    private fun fetchAndRegisterToken(activity: Activity) {
        try {
            val firebaseMessaging =
                Class.forName("com.google.firebase.messaging.FirebaseMessaging")

            val getInstance = firebaseMessaging.getMethod("getInstance")
            val instance = getInstance.invoke(null)

            val getToken = firebaseMessaging.getMethod("getToken")
            val task = getToken.invoke(instance)

            // Get Firebase project ID
            val firebaseApp = Class.forName("com.google.firebase.FirebaseApp")
            val getFirebaseInstance = firebaseApp.getMethod("getInstance")
            val appInstance = getFirebaseInstance.invoke(null)
            val getOptions = firebaseApp.getMethod("getOptions")
            val options = getOptions.invoke(appInstance)

            val firebaseOptions = Class.forName("com.google.firebase.FirebaseOptions")
            val getProjectId = firebaseOptions.getMethod("getProjectId")
            val projectId = getProjectId.invoke(options) as? String ?: ""

            // task is a com.google.android.gms.tasks.Task<String>
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
