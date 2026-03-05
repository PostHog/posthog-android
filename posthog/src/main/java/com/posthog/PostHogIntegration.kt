package com.posthog

/**
 * Integration interface for capturing events automatically or adding plugins to the PostHog SDK
 */
public interface PostHogIntegration {
    /**
     * Install the Integration after the SDK is setup
     * that requires a posthog instance to capture events
     */
    public fun install(postHog: PostHogInterface) {
    }

    /**
     * Uninstall the Integration after the SDK is closed
     */
    public fun uninstall() {
    }

    /**
     * Called when the remote config has been loaded.
     * Each integration is responsible for enabling or disabling features
     * based on the state of the remote config.
     */
    public fun onRemoteConfig() {
    }
}
