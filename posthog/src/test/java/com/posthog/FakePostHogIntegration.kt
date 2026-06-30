package com.posthog

internal class FakePostHogIntegration : PostHogIntegration {
    var installed = false
    var remoteConfigCount = 0
    var remoteConfigFailedCount = 0

    override fun install(postHog: PostHogInterface) {
        installed = true
    }

    override fun uninstall() {
        installed = false
    }

    override fun onRemoteConfig() {
        remoteConfigCount++
    }

    override fun onRemoteConfigFailed() {
        remoteConfigFailedCount++
    }
}
