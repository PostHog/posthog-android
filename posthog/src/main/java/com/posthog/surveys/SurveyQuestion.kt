package com.posthog.surveys

public abstract class SurveyQuestion(
    public open val question: String,
    public open val id: String,
    public open val type: SurveyQuestionType,
    public open val description: String?,
    public open val descriptionContentType: SurveyTextContentType?,
    public open val optional: Boolean?,
    public open val buttonText: String?,
    public open val originalQuestionIndex: Int?,
    public open val branching: SurveyQuestionBranching?,
)

public data class OpenSurveyQuestion(
    override val question: String,
    override val id: String,
    override val type: SurveyQuestionType = SurveyQuestionType.OPEN,
    override val description: String? = null,
    override val descriptionContentType: SurveyTextContentType? = null,
    override val optional: Boolean? = null,
    override val buttonText: String? = null,
    override val originalQuestionIndex: Int? = null,
    override val branching: SurveyQuestionBranching? = null,
) : SurveyQuestion(question, id, type, description, descriptionContentType, optional, buttonText, originalQuestionIndex, branching)

public data class LinkSurveyQuestion(
    override val question: String,
    override val id: String,
    override val type: SurveyQuestionType = SurveyQuestionType.LINK,
    override val description: String? = null,
    override val descriptionContentType: SurveyTextContentType? = null,
    override val optional: Boolean? = null,
    override val buttonText: String? = null,
    override val originalQuestionIndex: Int? = null,
    override val branching: SurveyQuestionBranching? = null,
    val link: String,
) : SurveyQuestion(question, id, type, description, descriptionContentType, optional, buttonText, originalQuestionIndex, branching)

public data class RatingSurveyQuestion(
    override val question: String,
    override val id: String,
    override val type: SurveyQuestionType = SurveyQuestionType.RATING,
    override val description: String? = null,
    override val descriptionContentType: SurveyTextContentType? = null,
    override val optional: Boolean? = null,
    override val buttonText: String? = null,
    override val originalQuestionIndex: Int? = null,
    override val branching: SurveyQuestionBranching? = null,
    val display: SurveyRatingDisplayType,
    val scale: Int,
    val lowerBoundLabel: String,
    val upperBoundLabel: String,
) : SurveyQuestion(question, id, type, description, descriptionContentType, optional, buttonText, originalQuestionIndex, branching)

public data class SingleSurveyQuestion(
    override val question: String,
    override val id: String,
    override val type: SurveyQuestionType = SurveyQuestionType.SINGLE_CHOICE,
    override val description: String? = null,
    override val descriptionContentType: SurveyTextContentType? = null,
    override val optional: Boolean? = null,
    override val buttonText: String? = null,
    override val originalQuestionIndex: Int? = null,
    override val branching: SurveyQuestionBranching? = null,
    val choices: List<String>,
    val hasOpenChoice: Boolean?,
    val shuffleOptions: Boolean?,
) : SurveyQuestion(question, id, type, description, descriptionContentType, optional, buttonText, originalQuestionIndex, branching)

public data class MultipleSurveyQuestion(
    override val question: String,
    override val id: String,
    override val type: SurveyQuestionType = SurveyQuestionType.MULTIPLE_CHOICE,
    override val description: String? = null,
    override val descriptionContentType: SurveyTextContentType? = null,
    override val optional: Boolean? = null,
    override val buttonText: String? = null,
    override val originalQuestionIndex: Int? = null,
    override val branching: SurveyQuestionBranching? = null,
    val choices: List<String>,
    val hasOpenChoice: Boolean?,
    val shuffleOptions: Boolean?,
) : SurveyQuestion(question, id, type, description, descriptionContentType, optional, buttonText, originalQuestionIndex, branching)
