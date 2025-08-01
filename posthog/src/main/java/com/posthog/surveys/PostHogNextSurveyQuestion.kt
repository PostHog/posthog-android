package com.posthog.surveys

/**
 * Represents the next question state after a user responds to a survey question.
 *
 * @property questionIndex The index of the next question to be displayed (0-based)
 * @property isSurveyCompleted Whether all questions have been answered and the survey is complete. Depending on the survey appearance configuration, you may want to show the "Thank you" message or dismiss the survey at this point
 */
public data class PostHogNextSurveyQuestion(
    val questionIndex: Int,
    val isSurveyCompleted: Boolean = false
)
