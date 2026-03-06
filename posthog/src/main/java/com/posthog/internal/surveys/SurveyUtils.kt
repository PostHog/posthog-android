package com.posthog.internal.surveys

import com.posthog.PostHogInternal
import com.posthog.surveys.Survey
import com.posthog.surveys.SurveySchedule

@PostHogInternal
public fun canActivateRepeatedly(survey: Survey): Boolean {
    return (survey.conditions?.events?.repeatedActivation == true && hasEvents(survey)) ||
        survey.schedule == SurveySchedule.ALWAYS
}

@PostHogInternal
public fun hasEvents(survey: Survey): Boolean {
    return survey.conditions?.events?.values?.isNotEmpty() == true
}
