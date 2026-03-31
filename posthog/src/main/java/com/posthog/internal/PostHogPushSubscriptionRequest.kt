package com.posthog.internal

import com.google.gson.annotations.SerializedName

/**
 * The request body for the push subscriptions API
 * @property apiKey the PostHog API Key
 * @property distinctId the user's distinct ID
 * @property token the device push token (FCM or APNS)
 * @property platform the platform ("android" or "ios")
 * @property appId the Firebase project_id (for Android) or APNS bundle_id (for iOS)
 */
internal data class PostHogPushSubscriptionRequest(
    @SerializedName("api_key")
    val apiKey: String,
    @SerializedName("distinct_id")
    val distinctId: String,
    val token: String,
    val platform: String,
    @SerializedName("app_id")
    val appId: String,
)
