package com.posthog.android.surveys.compose.internal

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
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
 * ## Surviving configuration changes
 *
 * A dialog window is bound to its host activity's token, so it must come down
 * when that activity is destroyed. To avoid losing the survey (and emitting a
 * spurious `survey dismissed`) on a rotation / dark-mode / font-size change, we
 * distinguish a configuration change from a genuine finish:
 * - **Configuration change** ([Activity.isChangingConfigurations]): snapshot the
 *   sheet's `rememberSaveable` state into a host-owned [SaveableStateRegistry],
 *   drop the window, and re-present on the recreated activity with that state
 *   restored verbatim. The survey stays active; no close event is fired.
 * - **Genuine finish**: dismiss and notify the SDK as usual.
 *
 * All UI mutation happens on the main thread; the public API is safe to call
 * from the SDK's survey thread.
 */
internal class PostHogSurveyHost(private val activityProvider: ActivityProvider) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var dialog: ComponentDialog? = null
    private var composeView: ComposeView? = null

    // The activity hosting the current dialog. Kept so we can react when that
    // specific activity is destroyed (vs. some other activity in the app).
    private var hostActivity: Activity? = null

    // Callbacks + survey for the active survey, retained so we can re-present it
    // on the recreated activity after a configuration change.
    private var currentSurvey: PostHogDisplaySurvey? = null
    private var onShownCallback: OnPostHogSurveyShown? = null
    private var onResponseCallback: OnPostHogSurveyResponse? = null
    private var onClosedCallback: OnPostHogSurveyClosed? = null

    // A pending delayed-show runnable (popup delay), so cleanup can cancel it.
    private var pendingShow: Runnable? = null

    // Whether `survey shown` has already been reported for the current survey,
    // so a re-present after a configuration change doesn't double-fire it.
    private var shownReported = false

    // The live registry backing the sheet's `rememberSaveable` state, plus the
    // snapshot taken across a configuration change. `pendingRepresent` marks that
    // the window was dropped for a config change and should be rebuilt on resume.
    private var saveableRegistry: SaveableStateRegistry? = null
    private var savedSurveyState: Map<String, List<Any?>>? = null
    private var pendingRepresent = false

    init {
        activityProvider.onActivityDestroyedListener = { destroyed ->
            if (destroyed === hostActivity) {
                if (destroyed.isChangingConfigurations) {
                    // Rotation / dark-mode / font-size / locale / fold: keep the
                    // survey alive and rebuild it on the recreated activity.
                    preserveForConfigChange()
                } else {
                    // Genuine finish: tear down and notify the SDK.
                    dismissInternal(notifyClosed = true)
                }
            }
        }
        activityProvider.onActivityResumedListener = { resumed ->
            if (pendingRepresent && currentSurvey != null) {
                pendingRepresent = false
                present(resumed)
            }
        }
    }

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

            currentSurvey = survey
            onShownCallback = onSurveyShown
            onResponseCallback = onSurveyResponse
            onClosedCallback = onSurveyClosed

            val present =
                Runnable {
                    pendingShow = null
                    present(activityProvider.foregroundActivity)
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

    private fun present(activity: Activity?) {
        val survey = currentSurvey ?: return

        if (activity == null || activity.isFinishing) {
            // Nothing to host the sheet on — report a dismiss so the SDK can
            // fire `survey dismissed` and move on.
            dismissInternal(notifyClosed = true)
            return
        }

        hostActivity = activity

        // Host-owned registry so the sheet's `rememberSaveable` state survives the
        // ComposeView being recreated across a configuration change. Seeded with
        // any snapshot taken before the previous window was dropped.
        val registry =
            SaveableStateRegistry(
                restoredValues = savedSurveyState,
                canBeSaved = { true },
            )
        saveableRegistry = registry
        savedSurveyState = null

        val composeView =
            ComposeView(activity).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                        SurveySheet(
                            survey = survey,
                            onSurveyShown = { reportShownOnce() },
                            onSubmit = { questionIndex, response ->
                                onResponseCallback?.invoke(survey, questionIndex, response)
                            },
                            onClose = { dismissInternal(notifyClosed = true) },
                        )
                    }
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
     * Forwards `survey shown` to the SDK at most once per survey, so re-presenting
     * after a configuration change doesn't emit a duplicate event.
     */
    private fun reportShownOnce() {
        if (shownReported) return
        val survey = currentSurvey ?: return
        shownReported = true
        onShownCallback?.invoke(survey)
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

    /**
     * Drops the dialog window for a configuration change while keeping the survey
     * active: snapshots the sheet's saveable state and arms a re-present on the
     * next foreground activity. No close event is fired.
     */
    private fun preserveForConfigChange() {
        pendingShow?.let {
            mainHandler.removeCallbacks(it)
            pendingShow = null
        }

        // Snapshot before disposing — providers unregister on disposal.
        savedSurveyState = saveableRegistry?.performSave()
        saveableRegistry = null

        composeView?.disposeComposition()
        dialog?.let { if (it.isShowing) it.dismiss() }

        dialog = null
        composeView = null
        hostActivity = null
        pendingRepresent = true
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
        hostActivity = null
        currentSurvey = null
        onShownCallback = null
        onResponseCallback = null
        onClosedCallback = null
        shownReported = false
        saveableRegistry = null
        savedSurveyState = null
        pendingRepresent = false

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
