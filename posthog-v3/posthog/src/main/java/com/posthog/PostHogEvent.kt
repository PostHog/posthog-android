package com.posthog

import com.google.gson.annotations.SerializedName
import java.util.Date
import java.util.UUID

public data class PostHogEvent(
    val event: String,
    val properties: Map<String, Any>,
    val timestamp: Date = Date(),
    val uuid: UUID = UUID.randomUUID(),
    @SerializedName("\$set")
    val userProperties: Map<String, Any>? = null,
//    @SerializedName("\$set_once")
//    val setOnce: Map<String, Any>? = null,
)
