package com.posthog

/**
 * Integration interface for capturing events automatically or adding plugins to the PostHog SDK
 */
public interface PostHogIntegration {
    /**
     * Install the Integration after the SDK is setup
     * that requires a posthog instance to capture events
     * @param postHog the configured SDK instance.
     */
    public fun install(postHog: PostHogInterface) {
    }

    /**
     * Uninstall the Integration after the SDK is closed
     */
    public fun uninstall() {
    }

    /**
     * Called when the remote config attempt for the current identity resolves. Each integration is
     * responsible for enabling or disabling features based on the state of the remote config.
     *
     * @param loaded true when a live remote config was received and applied; false when the attempt
     *   finished without a live response (e.g. the device is offline, or the request failed). On a
     *   failure no fresh config was applied, so integrations that buffered work while awaiting the
     *   live config should fall back to their cached state instead of waiting indefinitely.
     */
    public fun onRemoteConfig(loaded: Boolean = true) {
    }
}
