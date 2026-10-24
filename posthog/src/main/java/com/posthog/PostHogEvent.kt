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
 * The PostHog event data structure sent to capture
 * @property event The event name
 * @property distinctId The distinct Id
 * @property properties All the event properties
 * @property timestamp The event timestamp. UTC is preferred; the equivalent instant is serialized in UTC
 * @property uuid the UUID v7 is automatically generated and used for deduplication
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
    /**
     * Options that tell PostHog how to process this event, separate from its properties.
     *
     * Known keys are `process_person_profile`, `cookieless_mode`, `disable_skew_correction` and
     * `product_tour_id`; unknown keys are sent unchanged and ignored by PostHog. When the event is
     * sent, the legacy `$process_person_profile`, `$cookieless_mode`, `$ignore_sent_at` and
     * `$product_tour_id` properties are removed from [properties] and used for their option
     * unless that option is already set here.
     */
    var options: MutableMap<String, Any?>? = null,
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
