package com.posthog.surveys

/**
 * Localized overrides for a survey, keyed by language code (e.g. "fr", "pt-BR").
 *
 * The survey-level `description` is intentionally NOT translatable — it is only
 * used for internal previews and never rendered to end users.
 */
public data class SurveyTranslation(
    val name: String? = null,
    val thankYouMessageHeader: String? = null,
    val thankYouMessageDescription: String? = null,
    val thankYouMessageCloseButtonText: String? = null,
)
