package com.posthog.android.surveys.compose

import android.app.Application
import android.content.Context
import com.posthog.android.surveys.compose.internal.ActivityProvider
import com.posthog.android.surveys.compose.internal.PostHogSurveyHost
import com.posthog.surveys.OnPostHogSurveyClosed
import com.posthog.surveys.OnPostHogSurveyResponse
import com.posthog.surveys.OnPostHogSurveyShown
import com.posthog.surveys.PostHogDisplaySurvey
import com.posthog.surveys.PostHogSurveysResumeAwareDelegate

/**
 * Default Compose-based UI for PostHog surveys on Android.
 *
 * > Surveys must also be enabled in your PostHog project settings.
 *
 * ### Recommended: zero-wiring setup
 *
 * Add this module to your dependencies. Surveys are enabled by default
 * (`PostHogConfig.surveys = true`) and the core SDK auto-discovers this
 * delegate from the classpath, so you do **not** need to set
 * `surveysConfig.surveysDelegate` yourself:
 * ```
 * // build.gradle.kts
 * implementation("com.posthog:posthog-android-surveys-compose:<version>")
 *
 * // app init
 * val config = PostHogAndroidConfig(projectToken)
 * PostHogAndroid.setup(applicationContext, config)
 * ```
 *
 * You can still construct and assign it explicitly if you want to control the
 * lifecycle yourself:
 * ```
 * config.surveysConfig.surveysDelegate = PostHogSurveysComposeDelegate(applicationContext)
 * ```
 *
 * ### What's covered
 *
 * - All seven question / screen types: open text, single choice, multiple
 *   choice, number rating (NPS), emoji rating, link, and the thank-you screen
 * - Multi-question surveys with **server-driven branching** — navigation
 *   follows the next-question index the host SDK returns
 * - Thank-you / confirmation screen when the customer has enabled
 *   `displayThankYouMessage` in PostHog
 * - Intro screen before the first question when the customer has enabled
 *   `displayIntroScreen` in PostHog — advancing records no response
 * - The configured popup delay (`surveyPopupDelaySeconds`) before the sheet
 *   is shown
 * - Theming from `PostHogDisplaySurveyAppearance` — customer appearance config
 *   from the PostHog UI applies directly, with sensible defaults for anything
 *   left unset
 *
 * ### Known gaps (tracked follow-ups)
 *
 * - HTML question / thank-you / intro descriptions (rendered as plain text only)
 * - Dark-mode polish
 *
 * The constructor accepts any [Context] and resolves the [Application] from
 * it, so passing an activity context is safe.
 */
public class PostHogSurveysComposeDelegate(context: Context) : PostHogSurveysResumeAwareDelegate {
    override val supportsSurveyResume: Boolean = true

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
