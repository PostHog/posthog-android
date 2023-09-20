package com.posthog

import com.google.gson.annotations.SerializedName
import java.util.Date
import java.util.UUID

public data class PostHogEvent(
    val event: String,
    @SerializedName("\$distinct_id")
    val distinctId: String,
    val properties: Map<String, Any>? = null,
    val timestamp: Date = Date(),
    val uuid: UUID = UUID.randomUUID(),
)
