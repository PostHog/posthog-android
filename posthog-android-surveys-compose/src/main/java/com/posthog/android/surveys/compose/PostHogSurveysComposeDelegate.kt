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
 * > ⚠️ **Pre-1.0 (`0.x`).** This module's public surface may change between
 * > minor versions until `1.0.0`, and it is excluded from binary-compatibility
 * > validation. Surveys are also disabled by default
 * > (`PostHogConfig.surveys = false`) and require enabling in your PostHog
 * > project settings.
 *
 * ### Recommended: zero-wiring opt-in
 *
 * Add this module to your dependencies and enable surveys — the core SDK
 * auto-discovers this delegate from the classpath, so you do **not** need to
 * set `surveysConfig.surveysDelegate` yourself:
 * ```
 * // build.gradle.kts
 * implementation("com.posthog:posthog-android-surveys-compose:<version>")
 *
 * // app init
 * val config = PostHogAndroidConfig(projectToken).apply { surveys = true }
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
 * ### Font family
 *
 * The survey's `fontFamily` is a CSS font name, which Android can't load
 * directly. The sheet maps it to a font installed on the device instead, so
 * the result is the closest system font, not an exact match:
 *
 * - Only the first family of a CSS list is used, case-insensitively
 *   (`"Times New Roman", serif` is read as `times new roman`).
 * - `inherit`, `system-ui` or no value keep the host app's font.
 * - Web fonts are matched through Android's system font aliases. On stock
 *   Android, `Times New Roman` and `Georgia` render as the system serif font,
 *   `Courier New` as the system monospace font, and `Arial`, `Helvetica`,
 *   `Verdana` and `Tahoma` as the default sans-serif font, so they look the
 *   same as the app's default.
 * - Fonts the device doesn't know (e.g. `Trebuchet MS`) fall back to the
 *   default font.
 * - Bold text (question titles, selected choices) uses the matched font's
 *   bold weight.
 *
 * Aliases come from the device's system font config, so results can differ
 * between Android versions and manufacturers.
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
