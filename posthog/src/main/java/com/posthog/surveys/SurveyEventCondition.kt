package com.posthog.surveys

/**
 * An event that can trigger a survey, optionally narrowed by property filters.
 */
public data class SurveyEventCondition(
    /** The name of the event that triggers the survey. */
    val name: String,
    /**
     * Optional property filters, keyed by property name, that further constrain
     * when the event counts as a trigger.
     */
    val propertyFilters: Map<String, SurveyPropertyFilter>? = null,
)
