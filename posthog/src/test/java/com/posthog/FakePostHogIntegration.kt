package com.posthog

internal class FakePostHogIntegration : PostHogIntegration, PostHogOptInReceiver {
    var installed = false
    var installCount = 0
    var uninstallCount = 0
    var remoteConfigCount = 0
    var remoteConfigFailedCount = 0
    var onOptInCount = 0

    override fun install(postHog: PostHogInterface) {
        installed = true
        installCount++
    }

    override fun uninstall() {
        installed = false
        uninstallCount++
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
