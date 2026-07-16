package com.posthog.surveys

/**
 * The set of events that trigger a survey to be shown.
 */
public data class SurveyEventConditions(
    /**
     * Whether the survey can be re-triggered on every matching event, rather than
     * only the first time.
     */
    val repeatedActivation: Boolean?,
    /** The events (with optional property filters) that activate the survey. */
    val values: List<SurveyEventCondition>,
)
