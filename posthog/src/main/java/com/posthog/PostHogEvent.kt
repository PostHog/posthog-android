package com.posthog

import com.google.gson.annotations.SerializedName
import java.util.Date
import java.util.UUID

// should this be internal as well?

/**
 * The PostHog event data structure accepted by the batch API
 * @property event The event name
 * @property distinctId The distinct Id
 * @property properties All the event properties
 * @property timestamp The timestamp is automatically generated
 * @property uuid the UUID v4 is automatically generated and used for deduplication
 */
public data class PostHogEvent(
    val event: String,
    @SerializedName("distinct_id")
    val distinctId: String,
    val properties: Map<String, Any>? = null,
    val timestamp: Date = Date(),
    val uuid: UUID = UUID.randomUUID(),
)
