package com.posthog

internal class FakePostHogIntegration : PostHogIntegration {
    var installed = false

    override fun install(postHog: PostHogInterface) {
        installed = true
    }

    override fun uninstall() {
        installed = false
    }
}
