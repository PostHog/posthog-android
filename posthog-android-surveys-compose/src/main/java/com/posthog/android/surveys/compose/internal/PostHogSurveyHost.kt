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
import com.posthog.PostHog
import com.posthog.PostHogConfig
import com.posthog.android.surveys.compose.internal.ui.SurveySheet
import com.posthog.surveys.OnPostHogSurveyClosed
import com.posthog.surveys.OnPostHogSurveyResponse
import com.posthog.surveys.OnPostHogSurveyShown
import com.posthog.surveys.PostHogDisplaySurvey
import com.posthog.surveys.PostHogNextSurveyQuestion
import com.posthog.surveys.PostHogSurveyPresentation
import com.posthog.surveys.PostHogSurveyPresentationSession
import com.posthog.surveys.PostHogSurveyResponse
import com.posthog.surveys.PostHogSurveysConfig

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
 * ## Surviving host activity changes
 *
 * A dialog window is bound to its host activity's token, so it must come down
 * when that activity is destroyed. Snapshot the sheet's `rememberSaveable` state
 * into a host-owned [SaveableStateRegistry], drop the window, and re-present on
 * the next foreground activity. This applies both to configuration changes and
 * genuine Activity finishes: only an explicit survey close is a dismissal.
 * The survey stays active and no close event is fired during host teardown.
 *
 * All UI mutation happens on the main thread; the public API is safe to call
 * from the SDK's survey thread.
 */
internal class PostHogSurveyHost(private val activityProvider: ActivityProvider) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private val resetLock = Any()

    @Volatile private var minimumGeneration = 0L

    @Volatile private var activeOwner = PostHogSurveyPresentationSession(PostHogSurveysConfig())

    @Volatile private var session = Any()
    private var currentSession: Any? = null
    private var currentGeneration = 0L
    private var currentPresentation: Any? = null

    private var dialog: ComponentDialog? = null
    private var composeView: ComposeView? = null

    // The activity hosting the current dialog. Kept so we can react when that
    // specific activity is destroyed (vs. some other activity in the app).
    private var hostActivity: Activity? = null

    // Callbacks + survey for the active survey, retained so we can re-present it
    // on the recreated activity after a host activity change.
    private var currentSurvey: PostHogDisplaySurvey? = null
    private var onShownCallback: OnPostHogSurveyShown? = null
    private var onResponseCallback: OnPostHogSurveyResponse? = null
    private var onClosedCallback: OnPostHogSurveyClosed? = null

    // A pending delayed-show runnable (popup delay), so cleanup can cancel it.
    private var pendingShow: Runnable? = null

    // Whether `survey shown` has already been reported for the current survey,
    // so a re-present after a host activity change doesn't double-fire it.
    private var shownReported = false

    // Set when a show fired with no foreground activity to host the sheet (e.g. the
    // app was backgrounded during the popup delay). Rather than dismissing — which
    // would fire `survey dismissed` and mark the survey seen for a survey the user
    // never saw — we defer and re-present on the next resumed activity.
    private var awaitingForeground = false

    // The live registry backing the sheet's `rememberSaveable` state, plus the
    // snapshot taken across a host activity change. A non-null snapshot means a
    // re-present is armed: the window was dropped for a host activity change and should be
    // rebuilt on the next foreground activity.
    private var saveableRegistry: SaveableStateRegistry? = null
    private var savedSurveyState: Map<String, List<Any?>>? = null

    init {
        activityProvider.onActivityDestroyedListener = { destroyed ->
            if (destroyed === hostActivity) {
                // Rotation / dark-mode / font-size / locale / fold or a genuine finish:
                // keep the survey alive and rebuild it on the next foreground activity.
                preserveForHostChange()
                activityProvider.foregroundActivity?.takeIf { it !== destroyed }?.let(::present)
            }
        }
        activityProvider.onActivityResumedListener = { resumed ->
            // Re-present on the next foreground activity when either a host-change
            // snapshot is armed (window dropped for rotation/etc.) or a show was
            // deferred because no activity was available when it fired.
            if (currentSurvey != null && (savedSurveyState != null || awaitingForeground)) {
                present(resumed)
            }
        }
    }

    fun show(
        survey: PostHogDisplaySurvey,
        onSurveyShown: OnPostHogSurveyShown,
        onSurveyResponse: OnPostHogSurveyResponse,
        onSurveyClosed: OnPostHogSurveyClosed,
    ) = show(PostHogSurveyPresentation(survey, minimumGeneration, activeOwner), onSurveyShown, onSurveyResponse, onSurveyClosed)

    fun show(
        presentation: PostHogSurveyPresentation,
        onSurveyShown: OnPostHogSurveyShown,
        onSurveyResponse: OnPostHogSurveyResponse,
        onSurveyClosed: OnPostHogSurveyClosed,
    ) {
        bindSession(presentation.session)
        val survey = presentation.survey
        val resetGeneration = presentation.resetGeneration
        val presentationSession = advanceGeneration(resetGeneration, presentation.session) ?: return
        val delayMillis =
            ((survey.appearance?.surveyPopupDelaySeconds ?: 0.0).coerceAtLeast(0.0) * 1000).toLong()

        runOnMain {
            if (!canPresent(presentationSession, presentation)) return@runOnMain
            // Replace any in-flight survey first (notify the SDK it was closed).
            dismissInternal(notifyClosed = true)
            if (!canPresent(presentationSession, presentation)) return@runOnMain

            currentSession = presentationSession
            currentGeneration = resetGeneration
            currentPresentation = Any()
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

    private fun canPresent(
        presentationSession: Any,
        presentation: PostHogSurveyPresentation,
    ): Boolean = presentationSession === session && presentation.session.isActive && presentation.resetGeneration >= minimumGeneration

    fun bindSession(owner: PostHogSurveyPresentationSession) {
        val previousSession =
            synchronized(owner.config) {
                synchronized(resetLock) {
                    if (!owner.isActive) return
                    if (activeOwner === owner) {
                        minimumGeneration = maxOf(minimumGeneration, owner.config.resetGeneration)
                        return
                    }
                    val previous = session
                    activeOwner = owner
                    minimumGeneration = owner.config.resetGeneration
                    session = Any()
                    previous
                }
            }
        dismissSession(previousSession)
    }

    fun onReset(
        resetGeneration: Long,
        config: PostHogSurveysConfig,
    ) {
        val owner = activeOwner
        if (owner.config !== config) return
        val resetSession = advanceGeneration(resetGeneration, owner) ?: return
        runOnMain {
            if (currentSession === resetSession && currentGeneration < minimumGeneration) dismissInternal(notifyClosed = false)
        }
    }

    private fun advanceGeneration(
        resetGeneration: Long,
        owner: PostHogSurveyPresentationSession,
    ): Any? =
        synchronized(resetLock) {
            if (activeOwner !== owner || !owner.isActive) return@synchronized null
            minimumGeneration = maxOf(minimumGeneration, resetGeneration)
            session
        }

    fun cleanup(owner: PostHogSurveyPresentationSession = activeOwner) {
        val previousSession =
            synchronized(resetLock) {
                if (activeOwner !== owner) return
                val previous = session
                session = Any()
                previous
            }
        dismissSession(previousSession)
    }

    private fun dismissSession(previousSession: Any) {
        runOnMain { if (currentSession === previousSession) dismissInternal(notifyClosed = false) }
    }

    private fun present(activity: Activity?) {
        val survey = currentSurvey ?: return
        val presentation = currentPresentation ?: return
        if (!isCurrentPresentation(presentation)) {
            dismissInternal(notifyClosed = false)
            return
        }

        if (activity == null || activity.isFinishing) {
            // No foreground activity to host the sheet — e.g. the app was backgrounded
            // during the popup delay. Defer rather than dismiss: re-present on the next
            // resumed activity (see onActivityResumedListener) so we don't fire
            // `survey dismissed` / mark the survey seen for a survey the user never saw.
            awaitingForeground = true
            return
        }

        // Everything below links against Compose, supplied by the host at runtime, so an
        // incompatible version can throw a linkage `Error`. Guard it and, on failure, tear
        // the half-built survey down rather than crash the host.
        val presented =
            guard("presenting the survey") {
                awaitingForeground = false
                hostActivity = activity

                // Host-owned registry so the sheet's `rememberSaveable` state survives the
                // ComposeView being recreated across a host activity change. Seeded with
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
                                    onSurveyShown = { reportShownOnce(presentation) },
                                    onSubmit = { questionIndex, response ->
                                        if (isCurrentPresentation(
                                                presentation,
                                            )
                                        ) {
                                            onResponseCallback?.invoke(survey, questionIndex, response)
                                        } else {
                                            null
                                        }
                                    },
                                    onClose = { closePresentation(presentation) },
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

        if (!presented) {
            dismissInternal(notifyClosed = false)
        }
    }

    /**
     * Forwards `survey shown` to the SDK at most once per survey, so re-presenting
     * after a host activity change doesn't emit a duplicate event.
     */
    private fun isCurrentPresentation(presentation: Any): Boolean =
        currentPresentation === presentation && currentSession === session && activeOwner.isActive && currentGeneration >= minimumGeneration

    private fun closePresentation(presentation: Any) {
        if (isCurrentPresentation(presentation)) dismissInternal(notifyClosed = true)
    }

    private fun submitResponse(
        presentation: Any,
        survey: PostHogDisplaySurvey,
        index: Int,
        response: PostHogSurveyResponse,
    ): PostHogNextSurveyQuestion? = if (isCurrentPresentation(presentation)) onResponseCallback?.invoke(survey, index, response) else null

    private fun reportShownOnce(presentation: Any) {
        if (!isCurrentPresentation(presentation) || shownReported) return
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
     * Drops the dialog window for a host activity change while keeping the survey
     * active: snapshots the sheet's saveable state and arms a re-present on the
     * next foreground activity. No close event is fired.
     */
    private fun preserveForHostChange() {
        cancelPendingShow()

        guard("preserving the survey across a host activity change") {
            // Snapshot before disposing — providers unregister on disposal.
            savedSurveyState = saveableRegistry?.performSave()
            saveableRegistry = null

            composeView?.disposeComposition()
            dialog?.let { if (it.isShowing) it.dismiss() }
        }

        dialog = null
        composeView = null
        hostActivity = null
    }

    private fun dismissInternal(notifyClosed: Boolean) {
        cancelPendingShow()

        val activeDialog = dialog
        val activeView = composeView
        val survey = currentSurvey
        val onClosed = onClosedCallback
        // Only a survey that was actually shown can be "dismissed". Capture this before
        // the reset below so we never fire `survey dismissed` / mark seen for a survey the
        // user never saw (e.g. one deferred for a missing foreground activity, then replaced).
        val wasShown = shownReported

        dialog = null
        composeView = null
        hostActivity = null
        currentSurvey = null
        currentPresentation = null
        currentSession = null
        onShownCallback = null
        onResponseCallback = null
        onClosedCallback = null
        shownReported = false
        awaitingForeground = false
        saveableRegistry = null
        savedSurveyState = null

        guard("tearing down the survey") {
            activeView?.disposeComposition()
            activeDialog?.let { d ->
                if (d.isShowing) {
                    d.dismiss()
                }
            }
        }

        if (notifyClosed && wasShown && survey != null && onClosed != null) {
            onClosed(survey)
        }
    }

    private fun cancelPendingShow() {
        pendingShow?.let {
            mainHandler.removeCallbacks(it)
            pendingShow = null
        }
    }

    /**
     * Runs Compose-touching work, swallowing and logging any failure instead of letting it
     * crash the host app. Returns `true` when [block] completed, `false` when it threw.
     *
     * The host supplies the Compose runtime, so an incompatible version can throw a linkage
     * [Error] (e.g. `NoSuchMethodError`) rather than an [Exception] — hence the [Throwable]
     * catch. Callers decide how to recover; we'd rather fail to show and log than propagate.
     */
    private inline fun guard(
        action: String,
        block: () -> Unit,
    ): Boolean {
        return try {
            block()
            true
        } catch (t: Throwable) {
            PostHog.getConfig<PostHogConfig>()?.logger?.log(
                "Surveys: $action failed, skipping the survey. " +
                    "Likely a Compose version incompatibility in the host app. $t",
            )
            false
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
