package com.posthog.surveys

public data class SurveyEventCondition(
    val name: String,
    val propertyFilters: Map<String, SurveyPropertyFilter>? = null,
)
