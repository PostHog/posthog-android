package com.posthog.internal.surveys

import com.posthog.PostHogConfig
import com.posthog.surveys.SurveyType

internal class GsonSurveyTypeAdapter(config: PostHogConfig) :
    GsonSurveyEnumAdapter<SurveyType>(
        config,
        { it.value },
        { SurveyType.fromValue(it) },
    )
