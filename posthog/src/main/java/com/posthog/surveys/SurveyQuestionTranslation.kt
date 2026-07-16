package com.posthog.surveys

/**
 * Localized overrides for a survey question.
 *
 * Fields are a superset across question types: `link` only applies to link questions,
 * `lowerBoundLabel` / `upperBoundLabel` only to rating questions, and `choices` only
 * to single/multiple choice questions. Irrelevant fields are ignored for other types.
 */
public data class SurveyQuestionTranslation(
    /** Localized question text. */
    val question: String? = null,
    /** Localized question description. */
    val description: String? = null,
    /** Localized label for the button that advances past the question. */
    val buttonText: String? = null,
    /** Localized link URL (link questions only). */
    val link: String? = null,
    /** Localized label for the lowest rating (rating questions only). */
    val lowerBoundLabel: String? = null,
    /** Localized label for the highest rating (rating questions only). */
    val upperBoundLabel: String? = null,
    /** Localized answer options (choice questions only). */
    val choices: List<String>? = null,
)
