package com.posthog.surveys

import com.posthog.PostHogInternal

/** Optional presentation lifecycle used by SDK renderers to discard UI across reset. */
@PostHogInternal
public interface PostHogSurveysResetAwareDelegate : PostHogSurveysDelegate {
    /** Bind presentation ownership before rendering with a configuration. */
    public fun bindSurveySession(session: PostHogSurveyPresentationSession)

    /** Render only while this reset generation is current. */
    public fun renderSurvey(
        presentation: PostHogSurveyPresentation,
        onSurveyShown: OnPostHogSurveyShown,
        onSurveyResponse: OnPostHogSurveyResponse,
        onSurveyClosed: OnPostHogSurveyClosed,
    )

    /** Discard only presentations belonging to this installed integration. */
    public fun cleanupSurveys(session: PostHogSurveyPresentationSession)

    /** Discard presentations older than this generation, including pending and retained UI. */
    public fun onSurveyReset(
        resetGeneration: Long,
        config: PostHogSurveysConfig,
    )
}

/** Immutable ownership snapshot for a survey presentation. */
@PostHogInternal
public class PostHogSurveyPresentation(
    public val survey: PostHogDisplaySurvey,
    public val resetGeneration: Long,
    public val session: PostHogSurveyPresentationSession,
)

/** Identifies one installed integration; retired integrations cannot reclaim a renderer. */
@PostHogInternal
public class PostHogSurveyPresentationSession(public val config: PostHogSurveysConfig) {
    @Volatile
    public var isActive: Boolean = true
        private set

    public fun invalidate() {
        isActive = false
    }
}
