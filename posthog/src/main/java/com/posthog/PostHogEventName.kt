package com.posthog

public enum class PostHogEventName(
    /** The event name sent to PostHog. */
    public val event: String,
) {
    /** Session replay snapshot event. */
    SNAPSHOT("\$snapshot"),

    /** Sets person properties. */
    SET("\$set"),

    /** Identifies a user. */
    IDENTIFY("\$identify"),

    /** A screen view. */
    SCREEN("\$screen"),

    /** Sets group properties. */
    GROUP_IDENTIFY("\$groupidentify"),

    /** Aliases one distinct ID to another. */
    CREATE_ALIAS("\$create_alias"),

    /** A feature flag was evaluated. */
    FEATURE_FLAG_CALLED("\$feature_flag_called"),

    /** A feature was viewed. */
    FEATURE_VIEW("\$feature_view"),

    /** A feature was interacted with. */
    FEATURE_INTERACTION("\$feature_interaction"),

    /** A captured exception. */
    EXCEPTION("\$exception"),
    ;

    public companion object {
        public fun isUnsafeEditable(event: String): Boolean {
            return values().firstOrNull { it.event == event } != null
        }
    }
}
