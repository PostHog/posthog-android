package com.posthog

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
        public fun isUnsafeEditable(event: String): Boolean {
            return values().firstOrNull { it.event == event } != null
        }
    }
}
