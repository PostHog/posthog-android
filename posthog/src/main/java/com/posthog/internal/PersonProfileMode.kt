package com.posthog.internal

public enum class PersonProfileMode(public val mode: String) {
    NEVER("never"),
    ALWAYS("always"),
    IDENTIFIED_ONLY("identified_only"),
    ;

    public companion object {
        public fun fromConfig(config: String?): PersonProfileMode {
            return values().find { it.mode == config } ?: ALWAYS
        }
    }

    public fun shouldProcessProfile(event: String): Boolean {
        return when (this) {
            NEVER -> false
            ALWAYS -> true
            IDENTIFIED_ONLY -> event == "\$identify" || event == "\$create_alias" || event == "\$groupidentify"
        }
    }
}
