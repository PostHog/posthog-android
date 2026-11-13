package com.posthog

/**
 * Interface for being notified once feature flags has been loaded
 */
public fun interface PostHogOnFeatureFlags {
    /**
     * The method that is called when feature flags are loaded
     */
    public fun loaded()
}
