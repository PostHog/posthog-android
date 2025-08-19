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
 */
public data class PostHogDisplaySurvey(
    val id: String,
    val name: String,
    val questions: List<PostHogDisplaySurveyQuestion>,
    val appearance: PostHogDisplaySurveyAppearance? = null,
    val startDate: Date? = null,
    val endDate: Date? = null,
) {
    public companion object {
        /**
         * Creates a PostHogDisplaySurvey from a Survey object
         *
         * @param survey The Survey object to convert
         * @return A new PostHogDisplaySurvey instance
         */
        public fun toDisplaySurvey(survey: Survey): PostHogDisplaySurvey {
            return PostHogDisplaySurvey(
                id = survey.id,
                name = survey.name,
                questions = survey.questions.mapNotNull { PostHogDisplaySurveyQuestion.fromSurveyQuestion(it) },
                appearance = survey.appearance?.let { PostHogDisplaySurveyAppearance.fromSurveyAppearance(it) },
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
