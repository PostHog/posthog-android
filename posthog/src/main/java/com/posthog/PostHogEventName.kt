package com.posthog

public enum class PostHogEventName(public val event: String) {
    SNAPSHOT("\$snapshot"),
    SET("\$set"),
    IDENTIFY("\$identify"),
    SCREEN("\$screen"),
    GROUP_IDENTIFY("\$groupidentify"),
    CREATE_ALIAS("\$create_alias"),
    FEATURE_FLAG_CALLED("\$feature_flag_called"),
    EXCEPTION("\$exception"),
    ;

    public companion object {
        public fun isUnsafeEditable(event: String): Boolean {
            return values().firstOrNull { it.event == event } != null
        }
    }
}
