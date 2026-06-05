package com.posthog.android.surveys.compose.internal

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.graphics.drawable.toDrawable
import com.posthog.android.surveys.compose.internal.ui.SurveySheet
import com.posthog.surveys.OnPostHogSurveyClosed
import com.posthog.surveys.OnPostHogSurveyResponse
import com.posthog.surveys.OnPostHogSurveyShown
import com.posthog.surveys.PostHogDisplaySurvey

/**
 * Coordinator that presents the survey sheet in its **own window**, on top of
 * the foreground activity, and tears it down on dismiss / cleanup.
 *
 * ## Why a [ComponentDialog] (a separate window) rather than a child view?
 *
 * The survey must render above the host app without participating in its view
 * hierarchy, focus order, or navigation — so it gets its own window. A
 * [android.app.Dialog] owns its own [Window] token and is layered above the
 * activity. We use [ComponentDialog] specifically because it provides a
 * `LifecycleOwner`, `SavedStateRegistryOwner` and `OnBackPressedDispatcher` out
 * of the box — the `ViewTree*Owner`s a [ComposeView] needs to run — so Compose
 * works even when the host activity is a plain XML / AppCompat activity that
 * never set up Compose itself.
 *
 * The dialog window is transparent and undimmed; the `ModalBottomSheet` inside
 * supplies its own scrim, so the host app remains visible behind the sheet.
 * Only the explicit close button dismisses (touch-outside and back are
 * disabled).
 *
 * All UI mutation happens on the main thread; the public API is safe to call
 * from the SDK's survey thread.
 */
internal class PostHogSurveyHost(private val activityProvider: ActivityProvider) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var dialog: ComponentDialog? = null
    private var composeView: ComposeView? = null
    private var onClosedCallback: OnPostHogSurveyClosed? = null
    private var currentSurvey: PostHogDisplaySurvey? = null

    // A pending delayed-show runnable (popup delay), so cleanup can cancel it.
    private var pendingShow: Runnable? = null

    fun show(
        survey: PostHogDisplaySurvey,
        onSurveyShown: OnPostHogSurveyShown,
        onSurveyResponse: OnPostHogSurveyResponse,
        onSurveyClosed: OnPostHogSurveyClosed,
    ) {
        val delayMillis =
            ((survey.appearance?.surveyPopupDelaySeconds ?: 0.0).coerceAtLeast(0.0) * 1000).toLong()

        runOnMain {
            // Replace any in-flight survey first (notify the SDK it was closed).
            dismissInternal(notifyClosed = true)

            val present =
                Runnable {
                    pendingShow = null
                    present(survey, onSurveyShown, onSurveyResponse, onSurveyClosed)
                }

            if (delayMillis > 0L) {
                pendingShow = present
                mainHandler.postDelayed(present, delayMillis)
            } else {
                present.run()
            }
        }
    }

    fun cleanup() {
        runOnMain { dismissInternal(notifyClosed = false) }
    }

    private fun present(
        survey: PostHogDisplaySurvey,
        onSurveyShown: OnPostHogSurveyShown,
        onSurveyResponse: OnPostHogSurveyResponse,
        onSurveyClosed: OnPostHogSurveyClosed,
    ) {
        // Re-resolve the foreground activity at present time — it may have
        // changed during the popup delay.
        val activity = activityProvider.foregroundActivity
        if (activity == null || activity.isFinishing) {
            // Nothing to host the sheet on — report a dismiss so the SDK can
            // fire `survey dismissed` and move on.
            onSurveyClosed(survey)
            return
        }

        currentSurvey = survey
        onClosedCallback = onSurveyClosed

        val composeView =
            ComposeView(activity).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    SurveySheet(
                        survey = survey,
                        onSurveyShown = { onSurveyShown(survey) },
                        onSubmit = { questionIndex, response ->
                            onSurveyResponse(survey, questionIndex, response)
                        },
                        onClose = { dismissInternal(notifyClosed = true) },
                    )
                }
            }

        val componentDialog =
            ComponentDialog(activity).apply {
                setContentView(composeView)
                // X-button-only dismissal (swipe-down / touch-outside / back are ignored).
                setCancelable(false)
                setCanceledOnTouchOutside(false)
                configureWindow(window)
            }

        dialog = componentDialog
        this.composeView = composeView
        componentDialog.show()
    }

    /**
     * Makes the dialog window a transparent, full-screen, undimmed overlay so
     * the `ModalBottomSheet`'s own scrim shows through to the host app and the
     * sheet anchors to the bottom of the screen.
     */
    private fun configureWindow(window: Window?) {
        window ?: return
        window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
        // No system dim — the sheet draws its own scrim.
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }

    private fun dismissInternal(notifyClosed: Boolean) {
        pendingShow?.let {
            mainHandler.removeCallbacks(it)
            pendingShow = null
        }

        val activeDialog = dialog
        val activeView = composeView
        val survey = currentSurvey
        val onClosed = onClosedCallback

        dialog = null
        composeView = null
        currentSurvey = null
        onClosedCallback = null

        activeView?.disposeComposition()
        activeDialog?.let { d ->
            if (d.isShowing) {
                d.dismiss()
            }
        }

        if (notifyClosed && survey != null && onClosed != null) {
            onClosed(survey)
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}
