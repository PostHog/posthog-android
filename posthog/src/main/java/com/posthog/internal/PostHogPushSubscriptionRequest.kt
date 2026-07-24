package com.posthog.internal

import com.google.gson.annotations.SerializedName

/** Request body for the push subscriptions API. `appId` is the Firebase project_id (Android) or APNS bundle_id (iOS). */
internal data class PostHogPushSubscriptionRequest(
    @SerializedName("api_key")
    val projectToken: String,
    @SerializedName("distinct_id")
    val distinctId: String,
    @SerializedName("device_token")
    val deviceToken: String,
    val platform: String,
    @SerializedName("app_id")
    val appId: String,
)
