package com.posthog.internal

import com.google.gson.annotations.SerializedName
import com.posthog.PostHogInternal

/**
 * Request body for push subscription registration
 */
@PostHogInternal
public data class PostHogPushSubscriptionRequest(
    @SerializedName("api_key")
    val apiKey: String,
    @SerializedName("distinct_id")
    val distinctId: String,
    val token: String,
    val platform: String,
    @SerializedName("firebase_app_id")
    val firebaseAppId: String,
)
