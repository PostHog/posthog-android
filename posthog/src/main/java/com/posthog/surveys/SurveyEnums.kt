package com.posthog.surveys

public enum class SurveyType {
    POPOVER,
    API,
    WIDGET,
}

public enum class SurveyQuestionType {
    OPEN,
    LINK,
    RATING,
    MULTIPLE_CHOICE,
    SINGLE_CHOICE,
}

public enum class SurveyTextContentType {
    HTML,
    TEXT,
}

public enum class SurveyMatchType {
    REGEX,
    NOT_REGEX,
    EXACT,
    IS_NOT,
    I_CONTAINS,
    NOT_I_CONTAINS,
}

public enum class SurveyAppearancePosition {
    LEFT,
    RIGHT,
    CENTER,
}

public enum class SurveyAppearanceWidgetType {
    BUTTON,
    TAB,
    SELECTOR,
}

public enum class SurveyRatingDisplayType {
    NUMBER,
    EMOJI,
}

public enum class SurveyQuestionBranchingType {
    NEXT_QUESTION,
    END,
    RESPONSE_BASED,
    SPECIFIC_QUESTION,
}
