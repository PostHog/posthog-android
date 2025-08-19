package com.posthog.surveys

/**
 * Delegate responsible for managing survey presentation in your app.
 * Handles survey rendering, response collection, and lifecycle events.
 * You can provide your own delegate for a custom survey presentation.
 */
public interface PostHogSurveysDelegate {
    /**
     * Called when an activated PostHog survey needs to be rendered on the app's UI
     *
     * @param survey The survey to be displayed to the user
     * @param onSurveyShown To be called when the survey is successfully displayed to the user
     * @param onSurveyResponse To be called when the user submits a response to a question
     * @param onSurveyClosed To be called when the survey is dismissed
     */
    public fun renderSurvey(
        survey: PostHogDisplaySurvey,
        onSurveyShown: OnPostHogSurveyShown,
        onSurveyResponse: OnPostHogSurveyResponse,
        onSurveyClosed: OnPostHogSurveyClosed,
    )

    /**
     * Called when surveys are stopped to clean up any UI elements and reset the survey display state.
     * This method should handle the dismissal of any active surveys and cleanup of associated resources.
     */
    public fun cleanupSurveys()
}

/**
 * To be called when a survey is successfully shown to the user
 * @param survey The survey that was displayed
 */
public typealias OnPostHogSurveyShown = (survey: PostHogDisplaySurvey) -> Unit

/**
 * To be called when a user responds to a survey question
 * @param survey The current survey being displayed
 * @param index The index of the question being answered
 * @param response The user's response to the question
 * @return The next question state (next question index and completion flag)
 */
public typealias OnPostHogSurveyResponse = (
    survey: PostHogDisplaySurvey,
    index: Int,
    response: PostHogSurveyResponse,
) -> PostHogNextSurveyQuestion?

/**
 * To be called when a survey is dismissed
 * @param survey The survey that was closed
 */
public typealias OnPostHogSurveyClosed = (survey: PostHogDisplaySurvey) -> Unit
