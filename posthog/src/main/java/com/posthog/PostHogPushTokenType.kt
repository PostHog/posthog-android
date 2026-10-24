package com.posthog

import com.google.gson.annotations.SerializedName

/**
 * The kind of identifier passed to [PostHogInterface.registerPushNotificationToken].
 *
 * Firebase Messaging 25.1.0 deprecated the FCM registration token in favor of the Firebase
 * Installation ID (FID). An app runs in one mode or the other, so the SDK has to tell the
 * backend which one it registered: Workflows sends to the FCM `token` target for [FCM_TOKEN]
 * and to the `fid` target for [FID].
 */
public enum class PostHogPushTokenType {
    /** An FCM registration token from `FirebaseMessaging.getToken()` / `onNewToken`. */
    @SerializedName("fcm_token")
    FCM_TOKEN,

    /** A Firebase Installation ID from `FirebaseMessagingService.onRegistered` / `FirebaseInstallations.getId()`. */
    @SerializedName("fid")
    FID,
}
