package com.posthog

/**
 * Integration interface for capturing events automatically or adding plugins to the PostHog SDK
 */
public interface PostHogIntegration {
    /**
     * Install the Integration after the SDK is setup
     */
    public fun install()

    /**
     * Uninstall the Integration after the SDK is closed
     */
    public fun uninstall() {
    }
}
