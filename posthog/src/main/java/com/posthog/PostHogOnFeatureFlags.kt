package com.posthog

/**
 * Interface for being notified once feature flags has been loaded
 */
public fun interface PostHogOnFeatureFlags {
    /**
     * The method that is called when feature flags are loaded
     * @param featureFlags the Feature flags property, can be null if there was an error
     */
    public fun notify(featureFlags: Map<String, Any>?)
}
