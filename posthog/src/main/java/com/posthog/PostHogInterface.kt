package com.posthog

/**
 * The PostHog SDK entry point
 */
public interface PostHogInterface {
    /**
     * Setup the SDK
     * @param config the SDK configuration
     */
    public fun <T : PostHogConfig> setup(config: T)

    /**
     * Closes the SDK
     */
    public fun close()

    /**
     * Captures events
     * @param distinctId the distinctId, the generated [distinctId] is used if not given
     * @param properties the custom properties
     * @param userProperties the user properties, set as a "$set" property, Docs https://posthog.com/docs/product-analytics/user-properties
     * @param userPropertiesSetOnce the user properties to set only once, set as a "$set_once" property, Docs https://posthog.com/docs/product-analytics/user-properties
     * @param groups the group properties, set as a "$groups" property, Docs https://posthog.com/docs/product-analytics/group-analytics
     */
    public fun capture(
        event: String,
        distinctId: String? = null,
        properties: Map<String, Any>? = null,
        userProperties: Map<String, Any>? = null,
        userPropertiesSetOnce: Map<String, Any>? = null,
        groups: Map<String, Any>? = null,
    )

    /**
     * Identifies the user
     * Docs https://posthog.com/docs/product-analytics/identify
     * @param distinctId the distinctId
     * @param userProperties the user properties, set as a "$set" property, Docs https://posthog.com/docs/product-analytics/user-properties
     * @param userPropertiesSetOnce the user properties to set only once, set as a "$set_once" property, Docs https://posthog.com/docs/product-analytics/user-properties
     */
    public fun identify(
        distinctId: String,
        userProperties: Map<String, Any>? = null,
        userPropertiesSetOnce: Map<String, Any>? = null,
    )

    /**
     * Reloads the feature flags
     * @param onFeatureFlags the callback to get notified once feature flags is ready to use
     */
    public fun reloadFeatureFlags(onFeatureFlags: PostHogOnFeatureFlags? = null)

    /**
     * Returns if a feature flag is enabled, the feature flag must be a Boolean
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * @param key the Key
     * @param defaultValue the default value if not found, false if not given
     */
    public fun isFeatureEnabled(
        key: String,
        defaultValue: Boolean = false,
        distinctId: String? = null,
        groups: Map<String, Any>? = null,
    ): Boolean

    /**
     * Returns the feature flag
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * @param key the Key
     * @param defaultValue the default value if not found
     */
    public fun getFeatureFlag(
        key: String,
        defaultValue: Any? = null,
        distinctId: String? = null,
        groups: Map<String, Any>? = null,
    ): Any?

    /**
     * Returns the feature flag payload
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * @param key the Key
     * @param defaultValue the default value if not found
     */
    public fun getFeatureFlagPayload(
        key: String,
        defaultValue: Any? = null,
        distinctId: String? = null,
        groups: Map<String, Any>? = null,
    ): Any?

    /**
     * Flushes all the events in the Queue right away
     */
    public fun flush()

    /**
     * Resets all the cached properties including the [distinctId]
     * The SDK will behave as its been setup for the first time
     */
    public fun reset()

    /**
     * Enables the SDK to capture events
     */
    public fun optIn()

    /**
     * Disables the SDK to capture events until you [optIn] again
     */
    public fun optOut()

    /**
     * Creates a group
     * Docs https://posthog.com/docs/product-analytics/group-analytics
     * @param type the Group type
     * @param key the Group key
     * @param groupProperties the Group properties, set as a "$group_set" property, Docs https://posthog.com/docs/product-analytics/group-analytics
     */
    public fun group(
        type: String,
        key: String,
        groupProperties: Map<String, Any>? = null,
        distinctId: String? = null,
    )

    /**
     * Captures a screen view event
     * @param screenTitle the screen title
     * @param properties the custom properties
     */
    public fun screen(
        screenTitle: String,
        properties: Map<String, Any>? = null,
        distinctId: String? = null,
    )

    /**
     * Creates an alias for the user
     * Docs https://posthog.com/docs/product-analytics/identify#alias-assigning-multiple-distinct-ids-to-the-same-user
     * @param alias the alias
     */
    public fun alias(
        alias: String,
        distinctId: String? = null,
    )

    /**
     * Checks if the [optOut] mode is enabled or disabled
     */
    public fun isOptOut(): Boolean

    /**
     * Register a property to always be sent with all the following events until you call
     * [unregister] with the same key
     * PostHogPreferences.ALL_INTERNAL_KEYS are not allowed since they are internal and used by
     * the SDK only.
     * @param key the Key
     * @param value the Value
     */
    public fun register(
        key: String,
        value: Any,
    )

    /**
     * Unregisters the previously set property to be sent with all the following events
     * @param key the Key
     */
    public fun unregister(key: String)

    /**
     * Returns the registered [distinctId] property
     */
    public fun distinctId(): String

    /**
     * Enables or disables the debug mode
     */
    public fun debug(enable: Boolean = true)

    /**
     * Starts a session
     * The SDK will automatically start a session when you call [setup] on Android
     * On Android, the SDK will also automatically start a session when the app is in the foreground
     */
    public fun startSession()

    /**
     * Ends a session
     * The SDK will automatically end a session when you call [close]
     * On Android, the SDK will automatically end a session when the app is in the background
     * for at least 30 minutes
     */
    public fun endSession()

    /**
     * Returns if a session is active
     */
    public fun isSessionActive(): Boolean

    @PostHogInternal
    public fun <T : PostHogConfig> getConfig(): T?

    // TODO: get_all_flags, get_all_flags_and_payloads
    // This is useful when you need to fetch multiple flag values and don't want to make multiple requests.

    // TODO: local evaluation
}
