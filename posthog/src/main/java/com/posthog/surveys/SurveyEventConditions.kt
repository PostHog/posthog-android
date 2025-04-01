package com.posthog.surveys

public data class SurveyEventConditions(
    val repeatedActivation: Boolean?,
    val values: List<SurveyEventCondition>,
)
