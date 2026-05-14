package com.posthog.surveys

/**
 * Localized overrides for a survey's user-visible strings.
 *
 * Attached to [Survey.translations] as a map keyed by language code (e.g. "fr", "pt-BR").
 * All fields are optional — missing fields fall back to the original survey value.
 *
 * Note: the survey-level `description` is intentionally NOT translatable here.
 * It is only used for internal previews and never rendered to end users.
 */
public data class SurveyTranslation(
    val name: String? = null,
    val thankYouMessageHeader: String? = null,
    val thankYouMessageDescription: String? = null,
    val thankYouMessageCloseButtonText: String? = null,
)
