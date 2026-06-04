package com.posthog.internal.surveys

import com.posthog.PostHogConfig
import com.posthog.surveys.SurveyRatingDisplayType

internal class GsonSurveyRatingDisplayTypeAdapter(config: PostHogConfig) :
    GsonSurveyEnumAdapter<SurveyRatingDisplayType>(
        config,
        { it.value },
        { SurveyRatingDisplayType.fromValue(it) },
    )
