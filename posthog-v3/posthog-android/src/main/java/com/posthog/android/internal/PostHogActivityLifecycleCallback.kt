package com.posthog.android.internal

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
import com.posthog.PostHogIntegration

// ActivityLifecycleCallbacks, IntegrationOperation, PostHogActivityLifecycleCallbacks
internal class PostHogActivityLifecycleCallback(private val application: Application) : ActivityLifecycleCallbacks, PostHogIntegration {
    // TODO: lifecycle events, deeplinks, record screen views
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
    }

    override fun onActivityStarted(activity: Activity) {
    }

    override fun onActivityResumed(activity: Activity) {
    }

    override fun onActivityPaused(activity: Activity) {
    }

    override fun onActivityStopped(activity: Activity) {
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
    }

    override fun onActivityDestroyed(activity: Activity) {
    }

    override fun install() {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun uninstall() {
        application.unregisterActivityLifecycleCallbacks(this)
    }
}
