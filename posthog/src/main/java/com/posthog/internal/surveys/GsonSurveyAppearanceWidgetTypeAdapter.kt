package com.posthog.internal.surveys

import com.posthog.PostHogConfig
import com.posthog.surveys.SurveyAppearanceWidgetType

internal class GsonSurveyAppearanceWidgetTypeAdapter(config: PostHogConfig) :
    GsonSurveyEnumAdapter<SurveyAppearanceWidgetType>(
        config,
        { it.value },
        { SurveyAppearanceWidgetType.fromValue(it) },
    )
