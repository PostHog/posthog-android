package com.posthog

internal class FakePostHogIntegration : PostHogIntegration, PostHogOptInReceiver {
    var installed = false
    var remoteConfigCount = 0
    var remoteConfigFailedCount = 0
    var onOptInCount = 0

    override fun install(postHog: PostHogInterface) {
        installed = true
    }

    override fun uninstall() {
        installed = false
    }

    override fun onRemoteConfig(loaded: Boolean) {
        if (loaded) {
            remoteConfigCount++
        } else {
            remoteConfigFailedCount++
        }
    }

    override fun onOptIn() {
        onOptInCount++
    }
}
