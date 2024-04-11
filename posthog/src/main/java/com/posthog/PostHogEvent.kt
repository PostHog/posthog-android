package com.posthog

import com.google.gson.annotations.SerializedName
import java.util.Date
import java.util.UUID

// should this be internal as well?
// if we'd ever allow users to inspect and mutate the event, it should be a public API

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
    // refactor to use PostHogDateProvider
    val timestamp: Date = Date(),
    val uuid: UUID? = UUID.randomUUID(),
    @Deprecated("Do not use")
    val type: String? = null,
    @Deprecated("Do not use it, prefer [uuid]")
    @SerializedName("message_id")
    val messageId: UUID? = null,
    @Deprecated("Do not use it, prefer [properties]")
    @SerializedName("\$set")
    val set: Map<String, Any>? = null,
    // Only used for Replay
    @SerializedName("api_key")
    var apiKey: String? = null,
)
