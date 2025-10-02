package com.posthog.server

import java.util.Date

public sealed interface PostHogInterface {
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
     * Identifies the user
     * Docs https://posthog.com/docs/product-analytics/identify
     * @param distinctId the distinctId
     */
    public fun identify(distinctId: String) {
        identify(
            distinctId,
            null,
            null,
        )
    }

    /**
     * Identifies the user
     * Docs https://posthog.com/docs/product-analytics/identify
     * @param distinctId the distinctId
     * @param userProperties the user properties, set as a "$set" property, Docs https://posthog.com/docs/product-analytics/user-properties
     */
    public fun identify(
        distinctId: String,
        userProperties: Map<String, Any>? = null,
    ) {
        identify(
            distinctId,
            userProperties,
            null,
        )
    }

    /**
     * Flushes all the events in the Queue right away
     */
    public fun flush()

    /**
     * Enables or disables the debug mode
     */
    public fun debug(enable: Boolean = true)

    /**
     * Captures events
     * @param distinctId the distinctId of the user performing the event
     * @param properties the custom properties
     * @param userProperties the user properties, set as a "$set" property, Docs https://posthog.com/docs/product-analytics/user-properties
     * @param userPropertiesSetOnce the user properties to set only once, set as a "$set_once" property, Docs https://posthog.com/docs/product-analytics/user-properties
     * @param groups the groups, set as a "$groups" property, Docs https://posthog.com/docs/product-analytics/group-analytics
     */
    @JvmSynthetic
    public fun capture(
        distinctId: String,
        event: String,
        properties: Map<String, Any>? = null,
        userProperties: Map<String, Any>? = null,
        userPropertiesSetOnce: Map<String, Any>? = null,
        groups: Map<String, String>? = null,
        timestamp: Date? = null,
    )

    /**
     * Captures events
     * @param event the event name
     * @param distinctId the distinctId
     * @param options the capture options containing properties, userProperties, userPropertiesSetOnce, and groups
     */
    public fun capture(
        distinctId: String,
        event: String,
        options: PostHogCaptureOptions,
    ) {
        capture(
            distinctId,
            event,
            options.properties,
            options.userProperties,
            options.userPropertiesSetOnce,
            options.groups,
            options.timestamp,
        )
    }

    /**
     * Captures events
     * @param event the event name
     * @param distinctId the distinctId
     */
    public fun capture(
        distinctId: String,
        event: String,
    ) {
        capture(
            distinctId,
            event,
            null,
            null,
            null,
            null,
        )
    }

    /**
     * Returns if a feature flag is enabled, the feature flag must be a Boolean
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * @param distinctId the distinctId
     * @param key the Key
     * @param defaultValue the default value if not found, false if not given
     */
    public fun isFeatureEnabled(
        distinctId: String,
        key: String,
        defaultValue: Boolean = false,
    ): Boolean

    /**
     * Returns if a feature flag is enabled, the feature flag must be a Boolean
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * @param distinctId the distinctId
     * @param key the Key
     */
    public fun isFeatureEnabled(
        distinctId: String,
        key: String,
    ): Boolean {
        return isFeatureEnabled(distinctId, key, false)
    }

    /**
     * Returns the feature flag
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * @param distinctId the distinctId
     * @param key the Key
     * @param defaultValue the default value if not found
     */
    public fun getFeatureFlag(
        distinctId: String,
        key: String,
        defaultValue: Any? = null,
    ): Any?

    /**
     * Returns the feature flag
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * @param distinctId the distinctId
     * @param key the Key
     * @return the feature flag value or null if not found
     */
    public fun getFeatureFlag(
        distinctId: String,
        key: String,
    ): Any? {
        return getFeatureFlag(distinctId, key, null)
    }

    /**
     * Returns the feature flag payload
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * @param distinctId the distinctId
     * @param key the Key
     * @param defaultValue the default value if not found
     */
    public fun getFeatureFlagPayload(
        distinctId: String,
        key: String,
        defaultValue: Any? = null,
    ): Any?

    /**
     * Returns the feature flag payload
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * @param distinctId the distinctId
     * @param key the Key
     * @return the feature flag payload or null if not found
     */
    public fun getFeatureFlagPayload(
        distinctId: String,
        key: String,
    ): Any? {
        return getFeatureFlagPayload(distinctId, key, null)
    }

    /**
     * Creates a group
     * Docs https://posthog.com/docs/product-analytics/group-analytics
     * @param distinctId the distinctId
     * @param type the Group type
     * @param key the Group key
     * @param groupProperties the Group properties, set as a "$group_set" property, Docs https://posthog.com/docs/product-analytics/group-analytics
     */
    public fun group(
        distinctId: String,
        type: String,
        key: String,
        groupProperties: Map<String, Any>? = null,
    )

    /**
     * Creates a group
     * Docs https://posthog.com/docs/product-analytics/group-analytics
     * @param distinctId the distinctId
     * @param type the Group type
     * @param key the Group key
     */
    public fun group(
        distinctId: String,
        type: String,
        key: String,
    ) {
        group(distinctId, type, key, null)
    }

    /**
     * Creates an alias for the user
     * Docs https://posthog.com/docs/product-analytics/identify#alias-assigning-multiple-distinct-ids-to-the-same-user
     * @param distinctId the distinctId
     * @param alias the alias
     */
    public fun alias(
        distinctId: String,
        alias: String,
    )
}
