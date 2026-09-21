package com.posthog.android.internal

import android.app.Application
import android.content.ComponentName
import org.robolectric.Shadows.shadowOf

internal class InteractionTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        shadowOf(packageManager).addActivityIfNotPresent(ComponentName(this, InteractionActivity::class.java))
    }
}
