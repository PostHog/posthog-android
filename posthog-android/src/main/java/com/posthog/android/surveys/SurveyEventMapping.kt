package com.posthog.android.surveys

import com.posthog.surveys.SurveyEventCondition

internal class SurveyEventMapping(
    val surveyId: String,
    val condition: SurveyEventCondition,
)
