package com.posthog.server.internal

internal enum class PropertyType {
    COHORT,
    FLAG,
    PERSON,
    ;

    companion object {
        fun fromString(value: String): PropertyType {
            return when (value) {
                "cohort" -> COHORT
                "flag" -> FLAG
                "person" -> PERSON
                else -> PERSON
            }
        }

        fun fromStringOrNull(str: String?): PropertyType? {
            return str?.let { fromString(it) }
        }
    }
}
