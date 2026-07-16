package com.posthog.surveys

/**
 * A property filter applied to a survey trigger event, matching a property's value
 * against [values] using [operator].
 */
public class SurveyPropertyFilter(
    /** The values the property is matched against. */
    public val values: List<String>,
    /** The comparison operator used to evaluate the property value against [values]. */
    public val operator: SurveyMatchType,
)
