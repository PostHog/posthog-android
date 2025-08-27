package com.posthog.internal.surveys

import com.posthog.PostHogInternal

@PostHogInternal
public interface PostHogSurveysHandler {
    /**
     * To be called by Posthog when an event is captured
     */
    public fun onEvent(event: String)

    /**
     * Notifies the integration that surveys have been loaded or updated from remote config.
     * This should be called by PostHog when remote config loads and surveys are parsed.
     *
     * @param surveys List of surveys loaded from remote config (may be empty)
     */
    public fun onSurveysLoaded(surveys: List<com.posthog.surveys.Survey>)
}
