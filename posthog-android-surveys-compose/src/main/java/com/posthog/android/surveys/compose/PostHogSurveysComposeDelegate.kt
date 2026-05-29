package com.posthog.android.surveys.compose

import android.app.Application
import android.content.Context
import com.posthog.android.surveys.compose.internal.ActivityProvider
import com.posthog.android.surveys.compose.internal.PostHogSurveyHost
import com.posthog.surveys.OnPostHogSurveyClosed
import com.posthog.surveys.OnPostHogSurveyResponse
import com.posthog.surveys.OnPostHogSurveyShown
import com.posthog.surveys.PostHogDisplaySurvey
import com.posthog.surveys.PostHogSurveysDelegate

/**
 * Default Compose-based UI for PostHog surveys on Android.
 *
 * Opt-in by setting on PostHog SDK init:
 * ```
 * val config = PostHogAndroidConfig(apiKey).apply {
 *     surveys = true
 *     surveysConfig.surveysDelegate = PostHogSurveysComposeDelegate(applicationContext)
 * }
 * PostHogAndroid.setup(applicationContext, config)
 * ```
 *
 * MVP scope: NPS / Number Rating questions, Material 3 [ModalBottomSheet],
 * theming sourced from `PostHogDisplaySurveyAppearance`. Other question types
 * (open text, single/multi choice, link, emoji rating) and the thank-you
 * screen are explicitly out of scope until follow-up releases — they render
 * a placeholder instead of crashing.
 *
 * The constructor accepts any [Context] but treats it as an [Application]
 * context internally; passing an activity context is safe (we always resolve
 * the application from it).
 */
public class PostHogSurveysComposeDelegate(context: Context) : PostHogSurveysDelegate {
    private val application: Application = context.applicationContext as Application
    private val activityProvider: ActivityProvider = ActivityProvider()
    private val host: PostHogSurveyHost = PostHogSurveyHost(activityProvider)

    init {
        application.registerActivityLifecycleCallbacks(activityProvider)
    }

    override fun renderSurvey(
        survey: PostHogDisplaySurvey,
        onSurveyShown: OnPostHogSurveyShown,
        onSurveyResponse: OnPostHogSurveyResponse,
        onSurveyClosed: OnPostHogSurveyClosed,
    ) {
        host.show(
            survey = survey,
            onSurveyShown = onSurveyShown,
            onSurveyResponse = onSurveyResponse,
            onSurveyClosed = onSurveyClosed,
        )
    }

    override fun cleanupSurveys() {
        host.cleanup()
    }
}
