package com.posthog.surveys

private fun <T> enumFromValue(
    value: String,
    values: Array<T>,
    enumValue: (T) -> String,
): T? where T : Enum<T> = values.firstOrNull { enumValue(it) == value }

/**
 * How a survey is presented to the user.
 */
public enum class SurveyType(
    /** The wire value serialized for this survey type. */
    public val value: String,
) {
    POPOVER("popover"),
    API("api"),
    WIDGET("widget"),
    ;

    public companion object {
        /** Returns the [SurveyType] matching [value], or null if there is no match. */
        public fun fromValue(value: String): SurveyType? = enumFromValue(value, values()) { it.value }
    }
}

/**
 * The kind of a survey question.
 */
public enum class SurveyQuestionType(
    /** The wire value serialized for this question type. */
    public val value: String,
) {
    OPEN("open"),
    LINK("link"),
    RATING("rating"),
    MULTIPLE_CHOICE("multiple_choice"),
    SINGLE_CHOICE("single_choice"),
    ;

    public companion object {
        /** Returns the [SurveyQuestionType] matching [value], or null if there is no match. */
        public fun fromValue(value: String): SurveyQuestionType? = enumFromValue(value, values()) { it.value }
    }
}

/**
 * How text content (such as a question description) is rendered.
 */
public enum class SurveyTextContentType(
    /** The wire value serialized for this content type. */
    public val value: String,
) {
    HTML("html"),
    TEXT("text"),
    ;

    public companion object {
        /** Returns the [SurveyTextContentType] matching [value], or null if there is no match. */
        public fun fromValue(value: String): SurveyTextContentType? = enumFromValue(value, values()) { it.value }
    }
}

/**
 * The operator used to match a condition value against a target.
 */
public enum class SurveyMatchType(
    /** The wire value serialized for this match type. */
    public val value: String,
) {
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
        /** Returns the [SurveyMatchType] matching [value], or null if there is no match. */
        public fun fromValue(value: String): SurveyMatchType? = enumFromValue(value, values()) { it.value }
    }
}

/**
 * Where a survey popup is positioned on screen.
 */
public enum class SurveyAppearancePosition(
    /** The wire value serialized for this position. */
    public val value: String,
) {
    LEFT("left"),
    RIGHT("right"),
    CENTER("center"),
    ;

    public companion object {
        /** Returns the [SurveyAppearancePosition] matching [value], or null if there is no match. */
        public fun fromValue(value: String): SurveyAppearancePosition? = enumFromValue(value, values()) { it.value }
    }
}

/**
 * How a widget survey's trigger is presented.
 */
public enum class SurveyAppearanceWidgetType(
    /** The wire value serialized for this widget type. */
    public val value: String,
) {
    BUTTON("button"),
    TAB("tab"),
    SELECTOR("selector"),
    ;

    public companion object {
        /** Returns the [SurveyAppearanceWidgetType] matching [value], or null if there is no match. */
        public fun fromValue(value: String): SurveyAppearanceWidgetType? = enumFromValue(value, values()) { it.value }
    }
}

/**
 * Whether rating options are displayed as numbers or emojis.
 */
public enum class SurveyRatingDisplayType(
    /** The wire value serialized for this display type. */
    public val value: String,
) {
    NUMBER("number"),
    EMOJI("emoji"),
    ;

    public companion object {
        /** Returns the [SurveyRatingDisplayType] matching [value], or null if there is no match. */
        public fun fromValue(value: String): SurveyRatingDisplayType? = enumFromValue(value, values()) { it.value }
    }
}

/**
 * The kind of branching applied after a question is answered.
 */
public enum class SurveyQuestionBranchingType(
    /** The wire value serialized for this branching type. */
    public val value: String,
) {
    NEXT_QUESTION("next_question"),
    END("end"),
    RESPONSE_BASED("response_based"),
    SPECIFIC_QUESTION("specific_question"),
    ;

    public companion object {
        /** Returns the [SurveyQuestionBranchingType] matching [value], or null if there is no match. */
        public fun fromValue(value: String): SurveyQuestionBranchingType? = enumFromValue(value, values()) { it.value }
    }
}

/**
 * How often a survey may be shown to a user.
 */
public enum class SurveySchedule(
    /** The wire value serialized for this schedule. */
    public val value: String,
) {
    ONCE("once"),
    RECURRING("recurring"),
    ALWAYS("always"),
    ;

    public companion object {
        /** Returns the [SurveySchedule] matching [value], or null if there is no match. */
        public fun fromValue(value: String): SurveySchedule? = enumFromValue(value, values()) { it.value }
    }
}
