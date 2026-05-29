package com.posthog.android.surveys.compose.internal

import android.app.Activity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.posthog.android.surveys.compose.internal.ui.SurveySheet
import com.posthog.surveys.OnPostHogSurveyClosed
import com.posthog.surveys.OnPostHogSurveyResponse
import com.posthog.surveys.OnPostHogSurveyShown
import com.posthog.surveys.PostHogDisplaySurvey

/**
 * Singleton coordinator that attaches a [ComposeView] hosting the survey sheet
 * to the foreground activity, and tears it down on dismiss / cleanup.
 *
 * All UI mutation goes through the activity's UI thread because activities
 * always dispatch lifecycle callbacks there; the public API surface is
 * therefore safe to call from any thread that already holds a stable
 * reference to the host activity.
 */
internal class PostHogSurveyHost(private val activityProvider: ActivityProvider) {
    private var attachedView: ComposeView? = null
    private var attachedActivity: Activity? = null
    private var onClosedCallback: OnPostHogSurveyClosed? = null
    private var currentSurvey: PostHogDisplaySurvey? = null

    fun show(
        survey: PostHogDisplaySurvey,
        onSurveyShown: OnPostHogSurveyShown,
        onSurveyResponse: OnPostHogSurveyResponse,
        onSurveyClosed: OnPostHogSurveyClosed,
    ) {
        val activity = activityProvider.foregroundActivity
        if (activity == null) {
            // No foreground activity to host the sheet — let the SDK know this
            // was effectively a dismiss so it can fire `survey dismissed`.
            onSurveyClosed(survey)
            return
        }

        // Replace any in-flight survey first.
        dismiss(notifyClosed = true)

        currentSurvey = survey
        onClosedCallback = onSurveyClosed

        activity.runOnUiThread {
            val composeView =
                ComposeView(activity).apply {
                    setViewCompositionStrategy(
                        ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
                    )
                    setContent {
                        SurveySheet(
                            survey = survey,
                            onSurveyShown = { onSurveyShown(survey) },
                            onSubmit = { questionIndex, response ->
                                val next = onSurveyResponse(survey, questionIndex, response)
                                next?.isSurveyCompleted == true
                            },
                            onClose = {
                                dismiss(notifyClosed = true)
                            },
                        )
                    }
                }
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@runOnUiThread
            root.addView(
                composeView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            attachedView = composeView
            attachedActivity = activity
        }
    }

    fun cleanup() {
        dismiss(notifyClosed = false)
    }

    private fun dismiss(notifyClosed: Boolean) {
        val view = attachedView
        val activity = attachedActivity
        val survey = currentSurvey
        val onClosed = onClosedCallback

        attachedView = null
        attachedActivity = null
        currentSurvey = null
        onClosedCallback = null

        if (view != null && activity != null) {
            activity.runOnUiThread {
                (view.parent as? ViewGroup)?.removeView(view)
                view.disposeComposition()
            }
        }

        if (notifyClosed && survey != null && onClosed != null) {
            onClosed(survey)
        }
    }
}
