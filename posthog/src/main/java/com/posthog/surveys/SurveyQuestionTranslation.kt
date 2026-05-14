package com.posthog.surveys

/**
 * Localized overrides for a survey question's user-visible strings.
 *
 * Attached to [SurveyQuestion.translations] as a map keyed by language code (e.g. "fr", "pt-BR").
 * All fields are optional — missing fields fall back to the original question value.
 *
 * Fields are a superset across question types: `link` only applies to link questions,
 * `lowerBoundLabel` / `upperBoundLabel` only to rating questions, and `choices` only to
 * single/multiple choice questions. Irrelevant fields are ignored for other types.
 */
public data class SurveyQuestionTranslation(
    val question: String? = null,
    val description: String? = null,
    val buttonText: String? = null,
    val link: String? = null,
    val lowerBoundLabel: String? = null,
    val upperBoundLabel: String? = null,
    val choices: List<String>? = null,
)
