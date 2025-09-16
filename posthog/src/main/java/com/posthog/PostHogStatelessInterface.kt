package com.posthog

/**
 * The Stateless PostHog SDK entry point
 */
public interface PostHogStatelessInterface : PostHogCoreInterface {
    /**
     * Captures events
     * @param distinctId the distinctId of the user performing the event
     * @param properties the custom properties
     * @param userProperties the user properties, set as a "$set" property, Docs https://posthog.com/docs/product-analytics/user-properties
     * @param userPropertiesSetOnce the user properties to set only once, set as a "$set_once" property, Docs https://posthog.com/docs/product-analytics/user-properties
     * @param groups the groups, set as a "$groups" property, Docs https://posthog.com/docs/product-analytics/group-analytics
     */
    @JvmSynthetic
    public fun captureStateless(
        event: String,
        distinctId: String,
        properties: Map<String, Any>? = null,
        userProperties: Map<String, Any>? = null,
        userPropertiesSetOnce: Map<String, Any>? = null,
        groups: Map<String, String>? = null,
    )

    /**
     * Captures events
     * @param event the event name
     * @param distinctId the distinctId
     * @param options the capture options containing properties, userProperties, userPropertiesSetOnce, and groups
     */
    public fun captureStateless(
        event: String,
        distinctId: String,
        options: CaptureOptions,
    ) {
        captureStateless(
            event,
            distinctId,
            options.properties,
            options.userProperties,
            options.userPropertiesSetOnce,
            options.groups,
        )
    }

    /**
     * Captures events
     * @param event the event name
     * @param distinctId the distinctId
     */
    public fun captureStateless(
        event: String,
        distinctId: String,
    ) {
        captureStateless(
            event,
            distinctId,
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
    public fun isFeatureEnabledStateless(
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
    public fun isFeatureEnabledStateless(
        distinctId: String,
        key: String,
    ): Boolean {
        return isFeatureEnabledStateless(distinctId, key, false)
    }

    /**
     * Returns the feature flag
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * @param distinctId the distinctId
     * @param key the Key
     * @param defaultValue the default value if not found
     */
    public fun getFeatureFlagStateless(
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
    public fun getFeatureFlagStateless(
        distinctId: String,
        key: String,
    ): Any? {
        return getFeatureFlagStateless(distinctId, key, null)
    }

    /**
     * Returns the feature flag payload
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * @param distinctId the distinctId
     * @param key the Key
     * @param defaultValue the default value if not found
     */
    public fun getFeatureFlagPayloadStateless(
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
    public fun getFeatureFlagPayloadStateless(
        distinctId: String,
        key: String,
    ): Any? {
        return getFeatureFlagPayloadStateless(distinctId, key, null)
    }

    /**
     * Creates a group
     * Docs https://posthog.com/docs/product-analytics/group-analytics
     * @param distinctId the distinctId
     * @param type the Group type
     * @param key the Group key
     * @param groupProperties the Group properties, set as a "$group_set" property, Docs https://posthog.com/docs/product-analytics/group-analytics
     */
    public fun groupStateless(
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
    public fun groupStateless(
        distinctId: String,
        type: String,
        key: String,
    ) {
        groupStateless(distinctId, type, key, null)
    }

    /**
     * Creates an alias for the user
     * Docs https://posthog.com/docs/product-analytics/identify#alias-assigning-multiple-distinct-ids-to-the-same-user
     * @param distinctId the distinctId
     * @param alias the alias
     */
    public fun aliasStateless(
        distinctId: String,
        alias: String,
    )
}
