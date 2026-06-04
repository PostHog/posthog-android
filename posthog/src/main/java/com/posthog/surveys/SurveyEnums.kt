package com.posthog.surveys

private fun <T> enumFromValue(
    value: String,
    values: Array<T>,
    enumValue: (T) -> String,
): T? where T : Enum<T> = values.firstOrNull { enumValue(it) == value }

public enum class SurveyType(public val value: String) {
    POPOVER("popover"),
    API("api"),
    WIDGET("widget"),
    ;

    public companion object {
        public fun fromValue(value: String): SurveyType? = enumFromValue(value, values()) { it.value }
    }
}

public enum class SurveyQuestionType(public val value: String) {
    OPEN("open"),
    LINK("link"),
    RATING("rating"),
    MULTIPLE_CHOICE("multiple_choice"),
    SINGLE_CHOICE("single_choice"),
    ;

    public companion object {
        public fun fromValue(value: String): SurveyQuestionType? = enumFromValue(value, values()) { it.value }
    }
}

public enum class SurveyTextContentType(public val value: String) {
    HTML("html"),
    TEXT("text"),
    ;

    public companion object {
        public fun fromValue(value: String): SurveyTextContentType? = enumFromValue(value, values()) { it.value }
    }
}

public enum class SurveyMatchType(public val value: String) {
    REGEX("regex"),
    NOT_REGEX("not_regex"),
    EXACT("exact"),
    IS_NOT("is_not"),
    I_CONTAINS("icontains"),
    NOT_I_CONTAINS("not_icontains"),
    GT("gt"),
    LT("lt"),
    ;

    public companion object {
        public fun fromValue(value: String): SurveyMatchType? = enumFromValue(value, values()) { it.value }
    }
}

public enum class SurveyAppearancePosition(public val value: String) {
    LEFT("left"),
    RIGHT("right"),
    CENTER("center"),
    ;

    public companion object {
        public fun fromValue(value: String): SurveyAppearancePosition? = enumFromValue(value, values()) { it.value }
    }
}

public enum class SurveyAppearanceWidgetType(public val value: String) {
    BUTTON("button"),
    TAB("tab"),
    SELECTOR("selector"),
    ;

    public companion object {
        public fun fromValue(value: String): SurveyAppearanceWidgetType? = enumFromValue(value, values()) { it.value }
    }
}

public enum class SurveyRatingDisplayType(public val value: String) {
    NUMBER("number"),
    EMOJI("emoji"),
    ;

    public companion object {
        public fun fromValue(value: String): SurveyRatingDisplayType? = enumFromValue(value, values()) { it.value }
    }
}

public enum class SurveyQuestionBranchingType(public val value: String) {
    NEXT_QUESTION("next_question"),
    END("end"),
    RESPONSE_BASED("response_based"),
    SPECIFIC_QUESTION("specific_question"),
    ;

    public companion object {
        public fun fromValue(value: String): SurveyQuestionBranchingType? = enumFromValue(value, values()) { it.value }
    }
}

public enum class SurveySchedule(public val value: String) {
    ONCE("once"),
    RECURRING("recurring"),
    ALWAYS("always"),
    ;

    public companion object {
        public fun fromValue(value: String): SurveySchedule? = enumFromValue(value, values()) { it.value }
    }
}
