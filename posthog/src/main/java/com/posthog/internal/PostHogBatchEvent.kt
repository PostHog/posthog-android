package com.posthog.internal

import com.google.gson.annotations.SerializedName
import com.posthog.PostHogEvent
import java.util.Date

/**
 * The batch data structure for calling the batch API
 * @property apiKey the PostHog API Key
 * @property batch the events list
 * @property sentAt the timestamp of sending the event to calculate clock drifts
 */
internal data class PostHogBatchEvent(
    @SerializedName("api_key")
    val apiKey: String,
    val batch: List<PostHogEvent>,
    @SerializedName("sent_at")
    var sentAt: Date? = null,
)
