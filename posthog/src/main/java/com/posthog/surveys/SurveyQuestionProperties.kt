package com.posthog.surveys

public abstract class SurveyQuestionProperties {
    public abstract val question: String
    public abstract val description: String?
    public abstract val descriptionContentType: SurveyTextContentType?
    public abstract val optional: Boolean?
    public abstract val buttonText: String?
    public abstract val originalQuestionIndex: Int?
    public abstract val branching: SurveyQuestionBranching?
}
