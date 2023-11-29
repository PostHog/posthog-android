package com.posthog.android.internal

import android.content.Context
import com.posthog.internal.PostHogNetworkStatus

/**
 * Checks if there's an active network enabled
 * @property context the Config
 */
internal class PostHogAndroidNetworkStatus(private val context: Context) : PostHogNetworkStatus {
    override fun isConnected(): Boolean {
        return context.isConnected()
    }
}
