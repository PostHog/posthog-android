package com.posthog.surveys

public class SurveyPropertyFilter(
    public val values: List<String>,
    public val operator: SurveyMatchType,
)
