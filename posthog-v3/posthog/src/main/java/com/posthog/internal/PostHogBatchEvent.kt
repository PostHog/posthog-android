package com.posthog.internal

import com.google.gson.annotations.SerializedName
import com.posthog.PostHogEvent
import java.util.Date

internal data class PostHogBatchEvent(
    @SerializedName("api_key")
    val apiKey: String,
    val batch: List<PostHogEvent>,
    val timestamp: Date = Date(),
    @SerializedName("sent_at")
    var sentAt: Date? = null,
)
