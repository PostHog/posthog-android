package com.posthog

import com.google.gson.annotations.SerializedName
import java.util.Date

internal data class PostHogBatchEvent(
    @SerializedName("api_key")
    val apiKey: String,
    val batch: List<PostHogEvent>,
    val timestamp: Date = Date(),
)
