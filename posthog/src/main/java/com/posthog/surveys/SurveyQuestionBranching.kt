package com.posthog.surveys

public sealed class SurveyQuestionBranching {
    public object Next : SurveyQuestionBranching()

    public object End : SurveyQuestionBranching()

    public class ResponseBased(public val responseValues: Map<String, Any>) : SurveyQuestionBranching()

    public class SpecificQuestion(public val index: Int) : SurveyQuestionBranching()
}
