package com.posthog.surveys

public enum class SurveyType(public val value: String) {
    POPOVER("popover"),
    API("api"),
    WIDGET("widget"),
    ;

    public companion object {
        public fun fromValue(value: String): SurveyType? {
            return when (value) {
                "popover" -> POPOVER
                "api" -> API
                "widget" -> WIDGET
                else -> null
            }
        }
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
        public fun fromValue(value: String): SurveyQuestionType? {
            return when (value) {
                "open" -> OPEN
                "link" -> LINK
                "rating" -> RATING
                "multiple_choice" -> MULTIPLE_CHOICE
                "single_choice" -> SINGLE_CHOICE
                else -> null
            }
        }
    }
}

public enum class SurveyTextContentType(public val value: String) {
    HTML("html"),
    TEXT("text"),
    ;

    public companion object {
        public fun fromValue(value: String): SurveyTextContentType? {
            return when (value) {
                "html" -> HTML
                "text" -> TEXT
                else -> null
            }
        }
    }
}

public enum class SurveyMatchType(public val value: String) {
    REGEX("regex"),
    NOT_REGEX("not_regex"),
    EXACT("exact"),
    IS_NOT("is_not"),
    I_CONTAINS("icontains"),
    NOT_I_CONTAINS("not_icontains"),
    ;

    public companion object {
        public fun fromValue(value: String): SurveyMatchType? {
            return when (value) {
                "regex" -> REGEX
                "not_regex" -> NOT_REGEX
                "exact" -> EXACT
                "is_not" -> IS_NOT
                "icontains" -> I_CONTAINS
                "not_icontains" -> NOT_I_CONTAINS
                else -> null
            }
        }
    }
}

public enum class SurveyAppearancePosition(public val value: String) {
    LEFT("left"),
    RIGHT("right"),
    CENTER("center"),
    ;

    public companion object {
        public fun fromValue(value: String): SurveyAppearancePosition? {
            return when (value) {
                "left" -> LEFT
                "right" -> RIGHT
                "center" -> CENTER
                else -> null
            }
        }
    }
}

public enum class SurveyAppearanceWidgetType(public val value: String) {
    BUTTON("button"),
    TAB("tab"),
    SELECTOR("selector"),
    ;

    public companion object {
        public fun fromValue(value: String): SurveyAppearanceWidgetType? {
            return when (value) {
                "button" -> BUTTON
                "tab" -> TAB
                "selector" -> SELECTOR
                else -> null
            }
        }
    }
}

public enum class SurveyRatingDisplayType(public val value: String) {
    NUMBER("number"),
    EMOJI("emoji"),
    ;

    public companion object {
        public fun fromValue(value: String): SurveyRatingDisplayType? {
            return when (value) {
                "number" -> NUMBER
                "emoji" -> EMOJI
                else -> null
            }
        }
    }
}

public enum class SurveyQuestionBranchingType(public val value: String) {
    NEXT_QUESTION("next_question"),
    END("end"),
    RESPONSE_BASED("response_based"),
    SPECIFIC_QUESTION("specific_question"),
    ;

    public companion object {
        public fun fromValue(value: String): SurveyQuestionBranchingType? {
            return when (value) {
                "next_question" -> NEXT_QUESTION
                "end" -> END
                "response_based" -> RESPONSE_BASED
                "specific_question" -> SPECIFIC_QUESTION
                else -> null
            }
        }
    }
}
