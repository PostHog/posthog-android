package com.posthog.internal.surveys

import com.posthog.surveys.Survey
import com.posthog.surveys.SurveySchedule

public fun canActivateRepeatedly(survey: Survey): Boolean {
    return (survey.conditions?.events?.repeatedActivation == true && hasEvents(survey)) ||
        survey.schedule == SurveySchedule.ALWAYS
}

public fun hasEvents(survey: Survey): Boolean {
    return survey.conditions?.events?.values?.isNotEmpty() == true
}
