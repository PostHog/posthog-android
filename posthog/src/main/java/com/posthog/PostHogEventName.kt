package com.posthog

/**
 * Reserved PostHog event names used internally by the SDK.
 *
 * @property event The raw event name sent to PostHog (e.g. `$snapshot`).
 */
public enum class PostHogEventName(public val event: String) {
    SNAPSHOT("\$snapshot"),
    SET("\$set"),
    IDENTIFY("\$identify"),
    SCREEN("\$screen"),
    GROUP_IDENTIFY("\$groupidentify"),
    CREATE_ALIAS("\$create_alias"),
    FEATURE_FLAG_CALLED("\$feature_flag_called"),
    FEATURE_VIEW("\$feature_view"),
    FEATURE_INTERACTION("\$feature_interaction"),
    EXCEPTION("\$exception"),
    ;

    public companion object {
        /**
         * Returns true when [event] matches one of PostHog's reserved event names.
         *
         * Reserved events carry special meaning for PostHog and should not be captured
         * or renamed by user code.
         */
        public fun isUnsafeEditable(event: String): Boolean {
            return values().firstOrNull { it.event == event } != null
        }
    }
}
