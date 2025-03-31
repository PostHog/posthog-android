package com.posthog.surveys

public sealed class SurveyQuestionBranching {
    public object Next : SurveyQuestionBranching()

    public object End : SurveyQuestionBranching()

    public data class ResponseBased(val responseValues: Map<String, Any>) : SurveyQuestionBranching()

    public data class SpecificQuestion(val index: Int) : SurveyQuestionBranching()
}
