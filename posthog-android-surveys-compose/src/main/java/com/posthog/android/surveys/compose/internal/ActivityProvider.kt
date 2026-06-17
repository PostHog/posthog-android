package com.posthog.android.surveys.compose.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * Tracks the foreground activity so the surveys host can present its
 * [androidx.activity.ComponentDialog] over whichever activity is on top at
 * render time.
 *
 * Registered against the [Application] passed in
 * [com.posthog.android.surveys.compose.PostHogSurveysComposeDelegate].
 */
internal class ActivityProvider : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var foreground: WeakReference<Activity>? = null

    /**
     * Invoked on the main thread when an activity is destroyed, so a survey
     * hosted on it can be torn down before its dialog window leaks.
     */
    var onActivityDestroyedListener: ((Activity) -> Unit)? = null

    /**
     * Invoked on the main thread when an activity resumes, so a survey dropped
     * for a configuration change can be re-presented on the recreated activity.
     */
    var onActivityResumedListener: ((Activity) -> Unit)? = null

    val foregroundActivity: Activity?
        get() = foreground?.get()

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) = Unit

    override fun onActivityStarted(activity: Activity) {
        foreground = WeakReference(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        foreground = WeakReference(activity)
        onActivityResumedListener?.invoke(activity)
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) {
        if (foreground?.get() === activity) {
            foreground = null
        }
    }

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        if (foreground?.get() === activity) {
            foreground = null
        }
        onActivityDestroyedListener?.invoke(activity)
    }
}
