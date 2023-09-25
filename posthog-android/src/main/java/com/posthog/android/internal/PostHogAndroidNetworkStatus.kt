package com.posthog.android.internal

import android.content.Context
import com.posthog.PostHogNetworkStatus

internal class PostHogAndroidNetworkStatus(private val context: Context) : PostHogNetworkStatus {
    override fun isConnected(): Boolean {
        return context.isConnected()
    }
}
