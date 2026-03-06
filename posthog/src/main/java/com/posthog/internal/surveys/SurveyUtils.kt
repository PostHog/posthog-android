package com.posthog.internal.surveys

import com.posthog.PostHogInternal
import com.posthog.surveys.Survey
import com.posthog.surveys.SurveySchedule
import java.util.Date
import kotlin.math.abs
import kotlin.math.ceil

private const val SECONDS_PER_DAY = 86400.0

@PostHogInternal
public fun canActivateRepeatedly(survey: Survey): Boolean {
    return (survey.conditions?.events?.repeatedActivation == true && hasEvents(survey)) ||
        survey.schedule == SurveySchedule.ALWAYS
}

@PostHogInternal
public fun hasEvents(survey: Survey): Boolean {
    return survey.conditions?.events?.values?.isNotEmpty() == true
}

/**
 * Checks if the wait period has passed since the last seen survey date.
 * Returns true (survey should be shown) if:
 * - The survey has no wait period configured
 * - No survey has been seen before (lastSeenSurveyDate is null)
 * - The elapsed time since lastSeenSurveyDate exceeds the wait period
 */
@PostHogInternal
public fun hasWaitPeriodPassed(
    survey: Survey,
    lastSeenSurveyDate: Date?,
    now: Date,
): Boolean {
    val waitPeriodInDays = survey.conditions?.seenSurveyWaitPeriodInDays ?: return true
    val lastSeenDate = lastSeenSurveyDate ?: return true

    val diffSeconds = abs(now.time - lastSeenDate.time) / 1000.0
    val diffDays = ceil(diffSeconds / SECONDS_PER_DAY).toInt()
    return diffDays > waitPeriodInDays
}
