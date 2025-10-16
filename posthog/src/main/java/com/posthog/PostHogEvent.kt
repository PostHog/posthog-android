package com.posthog

import com.google.gson.annotations.SerializedName
import com.posthog.internal.errortracking.ThrowableCoercer.Companion.EXCEPTION_LEVEL_ATTRIBUTE
import com.posthog.internal.errortracking.ThrowableCoercer.Companion.EXCEPTION_LEVEL_FATAL
import com.posthog.vendor.uuid.TimeBasedEpochGenerator
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
    val properties: MutableMap<String, Any>? = null,
    // refactor to use PostHogDateProvider
    val timestamp: Date = Date(),
    val uuid: UUID? = TimeBasedEpochGenerator.generate(),
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
) {
    /**
     * Checks if the event is an exception event ($exception)
     */
    public fun isExceptionEvent(): Boolean {
        return event == PostHogEventName.EXCEPTION.event
    }

    /**
     * Checks if the event is a fatal exception event ($exception) and properties ($exception_level=fatal)
     */
    public fun isFatalExceptionEvent(): Boolean {
        return isExceptionEvent() && properties?.get(EXCEPTION_LEVEL_ATTRIBUTE) == EXCEPTION_LEVEL_FATAL
    }
}
