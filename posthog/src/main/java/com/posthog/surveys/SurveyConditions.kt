package com.posthog.surveys

public data class SurveyConditions(
    val url: String?,
    val urlMatchType: SurveyMatchType?,
    val selector: String?,
    val deviceTypes: List<String>?,
    val deviceTypesMatchType: SurveyMatchType?,
    val seenSurveyWaitPeriodInDays: Int?,
    val events: SurveyEventConditions?,
    // TODO: actions
)
