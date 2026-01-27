package com.posthog.internal

import com.posthog.PostHogInternal

/**
 * Request body for push subscription registration
 */
@PostHogInternal
public data class PostHogPushSubscriptionRequest(
    val api_key: String,
    val distinct_id: String,
    val token: String,
    val platform: String,
    val firebase_app_id: String,
)
