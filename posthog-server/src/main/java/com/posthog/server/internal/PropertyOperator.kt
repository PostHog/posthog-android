package com.posthog.server.internal

internal enum class PropertyOperator {
    UNKNOWN,
    EXACT,
    IS_NOT,
    IS_SET,
    IS_NOT_SET,
    ICONTAINS,
    NOT_ICONTAINS,
    REGEX,
    NOT_REGEX,
    IN,
    GT,
    GTE,
    LT,
    LTE,
    IS_DATE_BEFORE,
    IS_DATE_AFTER,
    FLAG_EVALUATES_TO,
    ;

    companion object {
        fun fromString(value: String): PropertyOperator {
            return when (value) {
                "exact" -> EXACT
                "is_not" -> IS_NOT
                "is_set" -> IS_SET
                "is_not_set" -> IS_NOT_SET
                "icontains" -> ICONTAINS
                "not_icontains" -> NOT_ICONTAINS
                "regex" -> REGEX
                "not_regex" -> NOT_REGEX
                "in" -> IN
                "gt" -> GT
                "gte" -> GTE
                "lt" -> LT
                "lte" -> LTE
                "is_date_before" -> IS_DATE_BEFORE
                "is_date_after" -> IS_DATE_AFTER
                "flag_evaluates_to" -> FLAG_EVALUATES_TO
                else -> UNKNOWN
            }
        }

        fun fromStringOrNull(str: String?): PropertyOperator? {
            return str?.let { fromString(it) }
        }
    }
}
