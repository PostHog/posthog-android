package com.posthog.internal

public enum class PersonProfiles(private val profileName: String) {
    NEVER("never"),
    ALWAYS("always"),
    IDENTIFIED_ONLY("identified_only"),
    ;

    override fun toString(): String {
        return profileName
    }
}
