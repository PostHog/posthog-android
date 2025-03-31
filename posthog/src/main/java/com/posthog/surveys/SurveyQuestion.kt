package com.posthog.surveys

public sealed class SurveyQuestion : SurveyQuestionProperties() {
    public data class Open(val data: OpenSurveyQuestion) : SurveyQuestion()

    public data class Link(val data: LinkSurveyQuestion) : SurveyQuestion()

    public data class Rating(val data: RatingSurveyQuestion) : SurveyQuestion()

    public data class SingleChoice(val data: MultipleSurveyQuestion) : SurveyQuestion()

    public data class MultipleChoice(val data: MultipleSurveyQuestion) : SurveyQuestion()

    override val question: String
        get() =
            when (this) {
                is Open -> data.question
                is Link -> data.question
                is Rating -> data.question
                is SingleChoice -> data.question
                is MultipleChoice -> data.question
            }

    override val description: String?
        get() =
            when (this) {
                is Open -> data.description
                is Link -> data.description
                is Rating -> data.description
                is SingleChoice -> data.description
                is MultipleChoice -> data.description
            }

    override val descriptionContentType: SurveyTextContentType?
        get() =
            when (this) {
                is Open -> data.descriptionContentType
                is Link -> data.descriptionContentType
                is Rating -> data.descriptionContentType
                is SingleChoice -> data.descriptionContentType
                is MultipleChoice -> data.descriptionContentType
            }

    override val optional: Boolean?
        get() =
            when (this) {
                is Open -> data.optional
                is Link -> data.optional
                is Rating -> data.optional
                is SingleChoice -> data.optional
                is MultipleChoice -> data.optional
            }

    override val buttonText: String?
        get() =
            when (this) {
                is Open -> data.buttonText
                is Link -> data.buttonText
                is Rating -> data.buttonText
                is SingleChoice -> data.buttonText
                is MultipleChoice -> data.buttonText
            }

    override val originalQuestionIndex: Int?
        get() =
            when (this) {
                is Open -> data.originalQuestionIndex
                is Link -> data.originalQuestionIndex
                is Rating -> data.originalQuestionIndex
                is SingleChoice -> data.originalQuestionIndex
                is MultipleChoice -> data.originalQuestionIndex
            }

    override val branching: SurveyQuestionBranching?
        get() =
            when (this) {
                is Open -> data.branching
                is Link -> data.branching
                is Rating -> data.branching
                is SingleChoice -> data.branching
                is MultipleChoice -> data.branching
            }
}

public data class OpenSurveyQuestion(
    override val question: String,
    override val description: String?,
    override val descriptionContentType: SurveyTextContentType?,
    override val optional: Boolean?,
    override val buttonText: String?,
    override val originalQuestionIndex: Int?,
    override val branching: SurveyQuestionBranching?,
) : SurveyQuestionProperties()

public data class LinkSurveyQuestion(
    override val question: String,
    override val description: String?,
    override val descriptionContentType: SurveyTextContentType?,
    override val optional: Boolean?,
    override val buttonText: String?,
    override val originalQuestionIndex: Int?,
    override val branching: SurveyQuestionBranching?,
    val link: String,
) : SurveyQuestionProperties()

public data class RatingSurveyQuestion(
    override val question: String,
    override val description: String?,
    override val descriptionContentType: SurveyTextContentType?,
    override val optional: Boolean?,
    override val buttonText: String?,
    override val originalQuestionIndex: Int?,
    override val branching: SurveyQuestionBranching?,
    val display: SurveyRatingDisplayType,
    val scale: Int,
    val lowerBoundLabel: String,
    val upperBoundLabel: String,
) : SurveyQuestionProperties()

public data class MultipleSurveyQuestion(
    override val question: String,
    override val description: String?,
    override val descriptionContentType: SurveyTextContentType?,
    override val optional: Boolean?,
    override val buttonText: String?,
    override val originalQuestionIndex: Int?,
    override val branching: SurveyQuestionBranching?,
    val choices: List<String>,
    val hasOpenChoice: Boolean?,
    val shuffleOptions: Boolean?,
) : SurveyQuestionProperties()
