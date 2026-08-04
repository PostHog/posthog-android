package com.posthog.android.internal

import com.posthog.PostHogIntegration
import com.posthog.PostHogInterface

/** Starts the connectivity monitor when a custom queue network status is configured. */
internal class PostHogAndroidNetworkStatusIntegration(
    private val networkStatus: PostHogAndroidNetworkStatus,
) : PostHogIntegration {
    override fun install(postHog: PostHogInterface) {
        networkStatus.start()
    }

    override fun uninstall() {
        networkStatus.unregister()
    }
}
