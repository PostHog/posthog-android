package com.posthog.surveys

public fun canActivateRepeatedly(survey: Survey): Boolean {
    return (survey.conditions?.events?.repeatedActivation == true && hasEvents(survey)) ||
        survey.schedule == SurveySchedule.ALWAYS
}

public fun hasEvents(survey: Survey): Boolean {
    return survey.conditions?.events?.values?.isNotEmpty() == true
}
