package com.posthog.internal.surveys

import com.posthog.PostHogConfig
import com.posthog.surveys.SurveySchedule

internal class GsonSurveyScheduleAdapter(config: PostHogConfig) :
    GsonSurveyEnumAdapter<SurveySchedule>(
        config,
        { it.value },
        { SurveySchedule.fromValue(it) },
    )
