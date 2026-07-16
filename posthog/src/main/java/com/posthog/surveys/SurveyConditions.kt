package com.posthog.surveys

/**
 * Targeting and display conditions that determine when a survey is eligible to be shown.
 */
public data class SurveyConditions(
    /** A URL that the current page must match for the survey to be eligible (web only). */
    val url: String?,
    /** How [url] is matched, for example exact, contains, or regex (web only). */
    val urlMatchType: SurveyMatchType?,
    /** A CSS selector that must be present on the page for the survey to show (web only). */
    val selector: String?,
    /** Device types the survey is restricted to. */
    val deviceTypes: List<String>?,
    /** How each entry in [deviceTypes] is matched. */
    val deviceTypesMatchType: SurveyMatchType?,
    /** Minimum number of days to wait after a user has seen any survey before showing this one. */
    val seenSurveyWaitPeriodInDays: Int?,
    /** Events that trigger the survey to be shown. */
    val events: SurveyEventConditions?,
    // TODO: actions
)
