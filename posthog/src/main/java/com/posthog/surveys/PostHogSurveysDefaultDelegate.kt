package com.posthog.surveys

import com.posthog.PostHogConfig

/**
 * Default implementation of PostHogSurveysDelegate.
 * This implementation doesn't render any UI, it only logs when survey methods are called.
 *
 * Users should implement their own delegate to handle survey rendering.
 */
public class PostHogSurveysDefaultDelegate public constructor(private var config: PostHogConfig? = null) : PostHogSurveysDelegate {
    /**
     * Called when a survey should be rendered.
     * This default implementation only logs that a survey was requested to be shown.
     *
     * @param survey The survey to be displayed to the user
     * @param onSurveyShown Callback to be invoked when the survey is shown
     * @param onSurveyResponse Callback to be invoked when the user responds to a question
     * @param onSurveyClosed Callback to be invoked when the survey is closed
     */
    override fun renderSurvey(
        survey: PostHogDisplaySurvey,
        onSurveyShown: OnPostHogSurveyShown,
        onSurveyResponse: OnPostHogSurveyResponse,
        onSurveyClosed: OnPostHogSurveyClosed,
    ) {
        // TODO: Handle default surveys UI
        config?.logger?.log("Survey requested to be shown: ${survey.id} - ${survey.name}")
        config?.logger?.log("Implement your own PostHogSurveysDelegate to render surveys")
    }

    /**
     * Called when surveys should be cleaned up.
     * This default implementation only logs that cleanup was requested.
     */
    override fun cleanupSurveys() {
        config?.logger?.log("Survey cleanup requested")
    }
}
