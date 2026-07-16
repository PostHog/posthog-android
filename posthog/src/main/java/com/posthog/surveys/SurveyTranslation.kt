package com.posthog.surveys

/**
 * Localized overrides for a survey, keyed by language code (e.g. "fr", "pt-BR").
 *
 * The survey-level `description` is intentionally NOT translatable — it is only
 * used for internal previews and never rendered to end users.
 */
public data class SurveyTranslation(
    /** Localized survey name. */
    val name: String? = null,
    /** Localized header for the confirmation message. */
    val thankYouMessageHeader: String? = null,
    /** Localized body for the confirmation message. */
    val thankYouMessageDescription: String? = null,
    /** Localized label for the confirmation message's close button. */
    val thankYouMessageCloseButtonText: String? = null,
)
