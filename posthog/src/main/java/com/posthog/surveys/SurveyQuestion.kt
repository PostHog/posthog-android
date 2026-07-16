package com.posthog.surveys

/**
 * Base type for a survey question, holding the fields common to every question type.
 *
 * Concrete subtypes ([OpenSurveyQuestion], [LinkSurveyQuestion], [RatingSurveyQuestion],
 * [SingleSurveyQuestion], [MultipleSurveyQuestion]) add the fields specific to their type.
 */
public open class SurveyQuestion {
    /** The question text shown to the user. */
    public val question: String? = null

    /** The unique identifier of the question. */
    public val id: String? = null

    /** The kind of question this is. */
    public val type: SurveyQuestionType? = null

    /** Optional supporting text shown alongside the question. */
    public val description: String? = null

    /** Whether [description] is rendered as HTML or plain text. */
    public val descriptionContentType: SurveyTextContentType? = null

    /** Whether the user may skip the question without answering. */
    public val optional: Boolean? = null

    /** The label on the button that advances past the question. */
    public val buttonText: String? = null

    /** Which question to show next based on this question's answer. */
    public val branching: SurveyQuestionBranching? = null

    /** Localized overrides for this question, keyed by language code. */
    public val translations: Map<String, SurveyQuestionTranslation>? = null
}

/**
 * A free-text question that accepts an open-ended written response.
 */
public class OpenSurveyQuestion : SurveyQuestion()

/**
 * A question that presents a link for the user to follow.
 */
public data class LinkSurveyQuestion(
    /** The URL the question directs the user to. */
    val link: String?,
) : SurveyQuestion()

// not a data class to avoid (int Integer.hashCode(int))

/**
 * A question that asks the user for a rating on a numeric or emoji scale.
 */
public class RatingSurveyQuestion(
    /** Whether ratings are shown as numbers or emojis. */
    public val display: SurveyRatingDisplayType?,
    /** The number of points on the rating scale (for example 3, 5, 7, or 10). */
    public val scale: Int?,
    /** The label describing the lowest rating. */
    public val lowerBoundLabel: String?,
    /** The label describing the highest rating. */
    public val upperBoundLabel: String?,
) : SurveyQuestion()

/**
 * A multiple-choice question where the user selects exactly one option.
 */
public data class SingleSurveyQuestion(
    /** The available answer options. */
    val choices: List<String>?,
    /** Whether the last choice is an open-ended "other" option the user can type into. */
    val hasOpenChoice: Boolean?,
    /** Whether to randomize the order in which choices are presented. */
    val shuffleOptions: Boolean?,
) : SurveyQuestion()

/**
 * A multiple-choice question where the user may select several options.
 */
public data class MultipleSurveyQuestion(
    /** The available answer options. */
    val choices: List<String>?,
    /** Whether the last choice is an open-ended "other" option the user can type into. */
    val hasOpenChoice: Boolean?,
    /** Whether to randomize the order in which choices are presented. */
    val shuffleOptions: Boolean?,
) : SurveyQuestion()
