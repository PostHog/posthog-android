package com.posthog.surveys

/**
 * Represents a user's response to a survey question.
 * Different response types are supported based on the question type.
 */
public sealed class PostHogSurveyResponse {
    /**
     * Response for an open text question.
     *
     * @property text The text response provided by the user
     */
    public data class Text(val text: String?) : PostHogSurveyResponse()

    /**
     * Response for a single choice question.
     *
     * @property selectedChoice The selected choice index
     * @property otherText Optional text for "other" option if available
     */
    public data class SingleChoice(val selectedChoice: String?) : PostHogSurveyResponse()

    /**
     * Response for a multiple choice question.
     *
     * @property selectedChoices List of selected choice indices
     * @property otherText Optional text for "other" option if available
     */
    public data class MultipleChoice(val selectedChoices: List<String>?) : PostHogSurveyResponse()

    /**
     * Response for a rating question.
     *
     * @property rating The rating value selected by the user
     */
    public data class Rating(val rating: Int?) : PostHogSurveyResponse()

    /**
     * Response for a link question.
     *
     * @property clicked Whether the link was clicked
     */
    public data class Link(val clicked: Boolean) : PostHogSurveyResponse()

    /**
     * Creates a response value for sending to the PostHog API.
     * This matches the Swift implementation behavior where responses are mapped to simple values.
     *
     * @return The response value, or null if no response (like unclicked link)
     */
    public fun toResponseValue(): Any? {
        return when (this) {
            is Text -> text
            is SingleChoice -> selectedChoice
            is MultipleChoice -> selectedChoices
            is Rating -> rating.toString()
            is Link -> if (clicked) "link clicked" else null
        }
    }
}
