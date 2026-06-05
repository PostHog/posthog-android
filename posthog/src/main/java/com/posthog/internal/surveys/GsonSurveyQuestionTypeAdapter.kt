package com.posthog.internal.surveys

import com.posthog.PostHogConfig
import com.posthog.surveys.SurveyQuestionType

internal class GsonSurveyQuestionTypeAdapter(config: PostHogConfig) :
    GsonSurveyEnumAdapter<SurveyQuestionType>(
        config,
        { it.value },
        { SurveyQuestionType.fromValue(it) },
    )
