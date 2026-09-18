package com.posthog.surveys

import java.util.Date

/**
 * Represents a survey that is ready to be displayed to the user.
 * Contains all the necessary information for rendering the survey UI.
 *
 * @property id The unique identifier of the survey
 * @property name The name of the survey
 * @property questions The list of questions in the survey
 * @property appearance The appearance configuration for the survey
 * @property startDate Optional date indicating when the survey should start being shown
 * @property endDate Optional date indicating when the survey should stop being shown
 * @property initialQuestionIndex The question to show when restoring unfinished progress; zero for a new survey.
 */
public data class PostHogDisplaySurvey(
    val id: String,
    val name: String,
    val questions: List<PostHogDisplaySurveyQuestion>,
    val appearance: PostHogDisplaySurveyAppearance? = null,
    val startDate: Date? = null,
    val endDate: Date? = null,
    val initialQuestionIndex: Int = 0,
) {
    // Preserve constructor and copy signatures used by already-compiled SDK consumers.
    public constructor(
        id: String,
        name: String,
        questions: List<PostHogDisplaySurveyQuestion>,
        appearance: PostHogDisplaySurveyAppearance? = null,
        startDate: Date? = null,
        endDate: Date? = null,
    ) : this(id, name, questions, appearance, startDate, endDate, 0)

    public fun copy(
        id: String = this.id,
        name: String = this.name,
        questions: List<PostHogDisplaySurveyQuestion> = this.questions,
        appearance: PostHogDisplaySurveyAppearance? = this.appearance,
        startDate: Date? = this.startDate,
        endDate: Date? = this.endDate,
    ): PostHogDisplaySurvey = PostHogDisplaySurvey(id, name, questions, appearance, startDate, endDate, initialQuestionIndex)

    // Kotlin's generated hashCode uses Integer.hashCode(int), unavailable on Android API 23.
    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + questions.hashCode()
        result = 31 * result + (appearance?.hashCode() ?: 0)
        result = 31 * result + (startDate?.hashCode() ?: 0)
        result = 31 * result + (endDate?.hashCode() ?: 0)
        return 31 * result + initialQuestionIndex
    }

    public companion object {
        /**
         * Creates a PostHogDisplaySurvey from a Survey object.
         *
         * @param survey The Survey object to convert.
         * @param surveyTranslation Optional resolved survey-level translation. When
         *   provided, overrides the survey `name` and `thankYouMessage*` fields.
         * @param questionTranslations Optional per-question translations, indexed
         *   positionally against [Survey.questions]. `null` entries leave the
         *   corresponding question untranslated.
         */
        public fun toDisplaySurvey(
            survey: Survey,
            surveyTranslation: SurveyTranslation? = null,
            questionTranslations: List<SurveyQuestionTranslation?>? = null,
        ): PostHogDisplaySurvey {
            val translatedQuestions =
                survey.questions.mapIndexedNotNull { index, question ->
                    PostHogDisplaySurveyQuestion.fromSurveyQuestion(
                        question,
                        questionTranslations?.getOrNull(index),
                    )
                }
            return PostHogDisplaySurvey(
                id = survey.id,
                name = surveyTranslation?.name ?: survey.name,
                questions = translatedQuestions,
                appearance =
                    survey.appearance?.let {
                        PostHogDisplaySurveyAppearance.fromSurveyAppearance(it, surveyTranslation)
                    },
                startDate = survey.startDate,
                endDate = survey.endDate,
            )
        }
    }
}

/**
 * Type of rating display for survey rating questions
 */
public enum class PostHogDisplaySurveyRatingType(public val value: Int) {
    /**
     * Display numeric rating options
     */
    NUMBER(0),

    /**
     * Display emoji rating options
     */
    EMOJI(1),
}

/**
 * Content type for text-based survey elements (e.g Question description, Thank you message description)
 */
public enum class PostHogDisplaySurveyTextContentType(public val value: Int) {
    /**
     * Content should be rendered as HTML
     */
    HTML(0),

    /**
     * Content should be rendered as plain text
     */
    TEXT(1),
}
