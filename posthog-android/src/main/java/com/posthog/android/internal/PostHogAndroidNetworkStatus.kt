package com.posthog.android.internal

import android.content.Context
import com.posthog.internal.PostHogNetworkStatus

public class PostHogAndroidNetworkStatus(private val context: Context) : PostHogNetworkStatus {
    override fun isConnected(): Boolean {
        return context.isConnected()
    }
}
