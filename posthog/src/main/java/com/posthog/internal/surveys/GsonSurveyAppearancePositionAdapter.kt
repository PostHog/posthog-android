package com.posthog.internal.surveys

import com.posthog.PostHogConfig
import com.posthog.surveys.SurveyAppearancePosition

internal class GsonSurveyAppearancePositionAdapter(config: PostHogConfig) :
    GsonSurveyEnumAdapter<SurveyAppearancePosition>(
        config,
        { it.value },
        { SurveyAppearancePosition.fromValue(it) },
    )
