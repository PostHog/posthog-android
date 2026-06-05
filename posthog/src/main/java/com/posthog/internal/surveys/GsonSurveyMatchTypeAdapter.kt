package com.posthog.internal.surveys

import com.posthog.PostHogConfig
import com.posthog.surveys.SurveyMatchType

internal class GsonSurveyMatchTypeAdapter(config: PostHogConfig) :
    GsonSurveyEnumAdapter<SurveyMatchType>(
        config,
        { it.value },
        { SurveyMatchType.fromValue(it) },
    )
