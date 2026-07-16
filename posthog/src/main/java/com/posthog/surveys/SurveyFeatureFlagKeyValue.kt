package com.posthog.surveys

/**
 * A feature flag condition that gates whether the survey is shown.
 */
public data class SurveyFeatureFlagKeyValue(
    /** Label identifying this entry on the survey (for example "flag1"), not the flag itself. */
    val key: String,
    /** Key of the feature flag that must be enabled for the survey to be shown. */
    val value: String?,
)
