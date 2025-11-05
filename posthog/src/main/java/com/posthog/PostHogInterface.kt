package com.posthog

import java.util.Date
import java.util.UUID

/**
 * The PostHog SDK entry point
 */
public interface PostHogInterface : PostHogCoreInterface {
    /**
     * Captures events
     * @param distinctId the distinctId, the generated [distinctId] is used if not given
     * @param properties the custom properties
     * @param userProperties the user properties, set as a "$set" property, Docs https://posthog.com/docs/product-analytics/user-properties
     * @param userPropertiesSetOnce the user properties to set only once, set as a "$set_once" property, Docs https://posthog.com/docs/product-analytics/user-properties
     * @param groups the groups, set as a "$groups" property, Docs https://posthog.com/docs/product-analytics/group-analytics
     * @param timestamp the timestamp for the event in UTC, if not provided the current time will be used
     */
    public fun capture(
        event: String,
        distinctId: String? = null,
        properties: Map<String, Any>? = null,
        userProperties: Map<String, Any>? = null,
        userPropertiesSetOnce: Map<String, Any>? = null,
        groups: Map<String, String>? = null,
        timestamp: Date? = null,
    )

    /**
     * Captures exceptions
     * @param throwable the Throwable error
     * @param properties the custom properties
     */
    public fun captureException(
        throwable: Throwable,
        properties: Map<String, Any>? = null,
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
    ): Any?

    /**
     * Resets all the cached properties including the [distinctId]
     * The SDK will behave as its been setup for the first time
     */
    public fun reset()

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
    )

    /**
     * Captures a screen view event
     * @param screenTitle the screen title
     * @param properties the custom properties
     */
    public fun screen(
        screenTitle: String,
        properties: Map<String, Any>? = null,
    )

    /**
     * Creates an alias for the user
     * Docs https://posthog.com/docs/product-analytics/identify#alias-assigning-multiple-distinct-ids-to-the-same-user
     * @param alias the alias
     */
    public fun alias(alias: String)

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
     * Starts a session
     * The SDK will automatically start a session when you call [setup]
     * On Android, the SDK will automatically start a session when the app is in the foreground
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

    /**
     * Returns if Session Replay is Active
     * Android only.
     */
    public fun isSessionReplayActive(): Boolean

    /**
     * Starts session replay.
     * This method will be NoOp if session replay is disabled in your project settings
     *
     * Android only.
     *
     * @param resumeCurrent Whether to resume session replay of current session (true) or start a new session (false).
     */
    public fun startSessionReplay(resumeCurrent: Boolean = true)

    /**
     * Stops the current session replay if one is in progress.
     *
     * Android only.
     */
    public fun stopSessionReplay()

    /**
     * Returns the session Id if a session is active
     */
    public fun getSessionId(): UUID?

    /**
     * Sets person properties that will be included in feature flag evaluation requests.
     *
     * @param properties Dictionary of person properties to include in flag evaluation
     * @param reloadFeatureFlags Whether to automatically reload feature flags after setting properties
     */
    public fun setPersonPropertiesForFlags(
        userProperties: Map<String, Any>,
        reloadFeatureFlags: Boolean = true,
    )

    /**
     * Resets all person properties that were set for feature flag evaluation.
     * @param reloadFeatureFlags Whether to automatically reload feature flags after resetting properties
     */
    public fun resetPersonPropertiesForFlags(reloadFeatureFlags: Boolean = true)

    /**
     * Sets properties for a specific group type to include when evaluating feature flags.
     *
     * @param type The group type identifier (e.g., "organization", "team")
     * @param properties Dictionary of properties to set for this group type
     * @param reloadFeatureFlags Whether to automatically reload feature flags after setting properties
     */
    public fun setGroupPropertiesForFlags(
        type: String,
        groupProperties: Map<String, Any>,
        reloadFeatureFlags: Boolean = true,
    )

    /**
     * Clears group properties for feature flag evaluation.
     *
     * @param type Optional group type to clear. If null, clears all group properties.
     * @param reloadFeatureFlags Whether to automatically reload feature flags after resetting properties
     */
    public fun resetGroupPropertiesForFlags(
        type: String? = null,
        reloadFeatureFlags: Boolean = true,
    )

    @PostHogInternal
    public fun <T : PostHogConfig> getConfig(): T?
}
