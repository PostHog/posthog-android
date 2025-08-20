package com.posthog.surveys

public open class SurveyQuestion {
    public val question: String? = null
    public val id: String? = null
    public val type: SurveyQuestionType? = null
    public val description: String? = null
    public val descriptionContentType: SurveyTextContentType? = null
    public val optional: Boolean? = null
    public val buttonText: String? = null
    public val branching: SurveyQuestionBranching? = null
}

public class OpenSurveyQuestion : SurveyQuestion()

public data class LinkSurveyQuestion(
    val link: String?,
) : SurveyQuestion()

// not a data class to avoid (int Integer.hashCode(int))
public class RatingSurveyQuestion(
    public val display: SurveyRatingDisplayType?,
    public val scale: Int?,
    public val lowerBoundLabel: String?,
    public val upperBoundLabel: String?,
) : SurveyQuestion()

public data class SingleSurveyQuestion(
    val choices: List<String>?,
    val hasOpenChoice: Boolean?,
    val shuffleOptions: Boolean?,
) : SurveyQuestion()

public data class MultipleSurveyQuestion(
    val choices: List<String>?,
    val hasOpenChoice: Boolean?,
    val shuffleOptions: Boolean?,
) : SurveyQuestion()
