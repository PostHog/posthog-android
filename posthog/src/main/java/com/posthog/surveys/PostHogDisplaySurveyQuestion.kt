package com.posthog.surveys

/**
 * Base class for all survey question types
 *
 * @property id The main question id
 * @property question The main question text to display
 * @property questionDescription Optional additional description or context for the question
 * @property questionDescriptionContentType Content type for the question description (HTML or plain text)
 * @property isOptional Whether the question can be skipped
 * @property buttonText Optional custom text for the question's action button
 */
public open class PostHogDisplaySurveyQuestion(
    public val id: String,
    public val question: String,
    public val questionDescription: String?,
    public val questionDescriptionContentType: PostHogDisplaySurveyTextContentType,
    public val isOptional: Boolean,
    public val buttonText: String?,
) {
    internal companion object {
        /**
         * Creates a display question from a survey question, applying the optional
         * per-question translation when provided.
         */
        internal fun fromSurveyQuestion(
            question: SurveyQuestion,
            translation: SurveyQuestionTranslation? = null,
        ): PostHogDisplaySurveyQuestion? {
            val id = question.id ?: ""
            val questionText = translation?.question ?: question.question ?: return null
            val description = translation?.description ?: question.description
            val buttonText = translation?.buttonText ?: question.buttonText
            val isOptional = question.optional ?: false
            val contentType =
                question.descriptionContentType?.let {
                    when (it) {
                        SurveyTextContentType.HTML -> PostHogDisplaySurveyTextContentType.HTML
                        SurveyTextContentType.TEXT -> PostHogDisplaySurveyTextContentType.TEXT
                    }
                } ?: PostHogDisplaySurveyTextContentType.TEXT

            return when (question.type) {
                SurveyQuestionType.OPEN ->
                    PostHogDisplayOpenQuestion(
                        id = id,
                        question = questionText,
                        questionDescription = description,
                        questionDescriptionContentType = contentType,
                        isOptional = isOptional,
                        buttonText = buttonText,
                    )

                SurveyQuestionType.LINK -> {
                    val originalLink = (question as? LinkSurveyQuestion)?.link
                    PostHogDisplayLinkQuestion(
                        id = id,
                        question = questionText,
                        questionDescription = description,
                        questionDescriptionContentType = contentType,
                        isOptional = isOptional,
                        buttonText = buttonText,
                        link = translation?.link ?: originalLink,
                    )
                }

                SurveyQuestionType.RATING -> {
                    if (question is RatingSurveyQuestion) {
                        val ratingType =
                            question.display?.let {
                                when (it) {
                                    SurveyRatingDisplayType.EMOJI -> PostHogDisplaySurveyRatingType.EMOJI
                                    SurveyRatingDisplayType.NUMBER -> PostHogDisplaySurveyRatingType.NUMBER
                                }
                            } ?: PostHogDisplaySurveyRatingType.NUMBER

                        val (scaleLower, scaleUpper) =
                            if (question.scale == 10) {
                                0 to 10
                            } else {
                                1 to (question.scale ?: 5)
                            }

                        PostHogDisplayRatingQuestion(
                            id = id,
                            question = questionText,
                            questionDescription = description,
                            questionDescriptionContentType = contentType,
                            isOptional = isOptional,
                            buttonText = buttonText,
                            ratingType = ratingType,
                            scaleLowerBound = scaleLower,
                            scaleUpperBound = scaleUpper,
                            lowerBoundLabel = translation?.lowerBoundLabel ?: question.lowerBoundLabel ?: "",
                            upperBoundLabel = translation?.upperBoundLabel ?: question.upperBoundLabel ?: "",
                        )
                    } else {
                        PostHogDisplayRatingQuestion(
                            id = id,
                            question = questionText,
                            questionDescription = description,
                            questionDescriptionContentType = contentType,
                            isOptional = isOptional,
                            buttonText = buttonText,
                            ratingType = PostHogDisplaySurveyRatingType.NUMBER,
                            scaleLowerBound = 1,
                            scaleUpperBound = 5,
                            lowerBoundLabel = translation?.lowerBoundLabel ?: "",
                            upperBoundLabel = translation?.upperBoundLabel ?: "",
                        )
                    }
                }

                SurveyQuestionType.SINGLE_CHOICE -> {
                    val single = question as? SingleSurveyQuestion
                    val choices = translation?.choices ?: single?.choices ?: emptyList()
                    PostHogDisplayChoiceQuestion(
                        id = id,
                        question = questionText,
                        questionDescription = description,
                        questionDescriptionContentType = contentType,
                        isOptional = isOptional,
                        buttonText = buttonText,
                        choices = choices,
                        hasOpenChoice = single?.hasOpenChoice ?: false,
                        shuffleOptions = single?.shuffleOptions ?: false,
                        isMultipleChoice = false,
                    )
                }

                SurveyQuestionType.MULTIPLE_CHOICE -> {
                    val multiple = question as? MultipleSurveyQuestion
                    val choices = translation?.choices ?: multiple?.choices ?: emptyList()
                    PostHogDisplayChoiceQuestion(
                        id = id,
                        question = questionText,
                        questionDescription = description,
                        questionDescriptionContentType = contentType,
                        isOptional = isOptional,
                        buttonText = buttonText,
                        choices = choices,
                        hasOpenChoice = multiple?.hasOpenChoice ?: false,
                        shuffleOptions = multiple?.shuffleOptions ?: false,
                        isMultipleChoice = true,
                    )
                }

                else -> null
            }
        }
    }
}

/**
 * Represents an open-ended question where users can input free-form text
 */
public class PostHogDisplayOpenQuestion(
    id: String,
    question: String,
    questionDescription: String?,
    questionDescriptionContentType: PostHogDisplaySurveyTextContentType,
    isOptional: Boolean,
    buttonText: String?,
) : PostHogDisplaySurveyQuestion(
        id = id,
        question = question,
        questionDescription = questionDescription,
        questionDescriptionContentType = questionDescriptionContentType,
        isOptional = isOptional,
        buttonText = buttonText,
    )

/**
 * Represents a question with a clickable link
 *
 * @property link The URL that will be opened when the link is clicked
 */
public class PostHogDisplayLinkQuestion(
    id: String,
    question: String,
    questionDescription: String?,
    questionDescriptionContentType: PostHogDisplaySurveyTextContentType,
    isOptional: Boolean,
    buttonText: String?,
    public val link: String?,
) : PostHogDisplaySurveyQuestion(
        id = id,
        question = question,
        questionDescription = questionDescription,
        questionDescriptionContentType = questionDescriptionContentType,
        isOptional = isOptional,
        buttonText = buttonText,
    )

/**
 * Represents a rating question where users can select a rating from a scale
 *
 * @property ratingType The type of rating scale (numbers, emoji)
 * @property scaleLowerBound The lower bound of the rating scale
 * @property scaleUpperBound The upper bound of the rating scale
 * @property lowerBoundLabel The label for the lower bound of the rating scale
 * @property upperBoundLabel The label for the upper bound of the rating scale
 */
public class PostHogDisplayRatingQuestion(
    id: String,
    question: String,
    questionDescription: String?,
    questionDescriptionContentType: PostHogDisplaySurveyTextContentType,
    isOptional: Boolean,
    buttonText: String?,
    public val ratingType: PostHogDisplaySurveyRatingType,
    public val scaleLowerBound: Int,
    public val scaleUpperBound: Int,
    public val lowerBoundLabel: String,
    public val upperBoundLabel: String,
) : PostHogDisplaySurveyQuestion(
        id = id,
        question = question,
        questionDescription = questionDescription,
        questionDescriptionContentType = questionDescriptionContentType,
        isOptional = isOptional,
        buttonText = buttonText,
    )

/**
 * Represents a question where users can select one or more choices
 *
 * @property choices The list of options for the user to choose from
 * @property hasOpenChoice Whether the question includes an "other" option for users to input free-form text
 * @property shuffleOptions Whether the options should be shuffled when presented
 * @property isMultipleChoice Whether the user can select multiple options
 */
public class PostHogDisplayChoiceQuestion(
    id: String,
    question: String,
    questionDescription: String?,
    questionDescriptionContentType: PostHogDisplaySurveyTextContentType,
    isOptional: Boolean,
    buttonText: String?,
    public val choices: List<String>,
    public val hasOpenChoice: Boolean,
    public val shuffleOptions: Boolean,
    public val isMultipleChoice: Boolean,
) : PostHogDisplaySurveyQuestion(
        id = id,
        question = question,
        questionDescription = questionDescription,
        questionDescriptionContentType = questionDescriptionContentType,
        isOptional = isOptional,
        buttonText = buttonText,
    )
