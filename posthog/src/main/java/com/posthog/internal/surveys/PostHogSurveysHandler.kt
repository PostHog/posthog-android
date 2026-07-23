package com.posthog.internal.surveys

import com.posthog.PostHogInternal
import com.posthog.surveys.Survey

@PostHogInternal
public interface PostHogSurveysHandler {
    /**
     * To be called by Posthog when an event is captured
     */
    public fun onEvent(
        event: String,
        properties: Map<String, Any>? = null,
    )

    /**
     * Notifies the integration that surveys have been loaded or updated from remote config.
     * This should be called by PostHog when remote config loads and surveys are parsed.
     *
     * @param surveys List of surveys loaded from remote config (may be empty)
     */
    public fun onSurveysLoaded(surveys: List<Survey>)

    /**
     * Displays the survey with the given ID on demand, bypassing display conditions
     * such as targeting flags, event triggers, and the seen/wait-period checks.
     *
     * @param surveyId The ID of the survey to display
     */
    public fun displaySurvey(surveyId: String)
}
