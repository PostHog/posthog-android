package com.posthog.server

import com.posthog.FeatureFlagResult
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
        userProperties: Map<String, Any>?,
        userPropertiesSetOnce: Map<String, Any>?,
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
        userProperties: Map<String, Any>?,
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
    public fun debug(enable: Boolean)

    /**
     * Captures events
     * @param distinctId the distinctId of the user performing the event
     * @param event the event name
     * @param properties the custom properties
     * @param userProperties the user properties, set as a "$set" property, Docs https://posthog.com/docs/product-analytics/user-properties
     * @param userPropertiesSetOnce the user properties to set only once, set as a "$set_once" property, Docs https://posthog.com/docs/product-analytics/user-properties
     * @param groups the groups, set as a "$groups" property, Docs https://posthog.com/docs/product-analytics/group-analytics
     * @param timestamp the timestamp for the event
     * @param appendFeatureFlags when true, enriches the event with feature flag properties
     * @param flags optional pre-resolved snapshot from [evaluateFlags]; when supplied, attaches
     *   `$feature/<key>` and `$active_feature_flags` from the snapshot without making another
     *   `/flags` request. Takes precedence over [appendFeatureFlags] when both are set.
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
        appendFeatureFlags: Boolean = false,
        flags: PostHogFeatureFlagEvaluations? = null,
    )

    /**
     * Captures events
     * @param distinctId the distinctId
     * @param event the event name
     * @param options the capture options containing properties, userProperties, userPropertiesSetOnce, groups, and appendFeatureFlags
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
            options.appendFeatureFlags,
            options.flags,
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
            null,
            false,
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
    @Deprecated(
        message =
            "Prefer evaluateFlags(distinctId).isEnabled(key) — fewer /flags requests when " +
                "the same identity is consulted multiple times. Will be removed in the next major.",
    )
    public fun isFeatureEnabled(
        distinctId: String,
        key: String,
        defaultValue: Boolean = false,
        groups: Map<String, String>? = null,
        personProperties: Map<String, Any?>? = null,
        groupProperties: Map<String, Map<String, Any?>>? = null,
    ): Boolean

    /**
     * Returns if a feature flag is enabled, the feature flag must be a Boolean
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * @param distinctId the distinctId
     * @param key the Key
     */
    @Deprecated(
        message = "Prefer evaluateFlags(distinctId).isEnabled(key). Will be removed in the next major.",
    )
    public fun isFeatureEnabled(
        distinctId: String,
        key: String,
    ): Boolean {
        @Suppress("DEPRECATION")
        return isFeatureEnabled(
            distinctId,
            key,
            defaultValue = false,
            groups = null,
            personProperties = null,
            groupProperties = null,
        )
    }

    /**
     * Returns if a feature flag is enabled, the feature flag must be a Boolean
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * @param distinctId the distinctId
     * @param key the Key
     * @param defaultValue the default value if not found
     */
    @Deprecated(
        message = "Prefer evaluateFlags(distinctId).isEnabled(key). Will be removed in the next major.",
    )
    public fun isFeatureEnabled(
        distinctId: String,
        key: String,
        defaultValue: Boolean,
    ): Boolean {
        @Suppress("DEPRECATION")
        return isFeatureEnabled(
            distinctId,
            key,
            defaultValue = defaultValue,
            groups = null,
            personProperties = null,
            groupProperties = null,
        )
    }

    /**
     * Returns if a feature flag is enabled, the feature flag must be a Boolean
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * @param distinctId the distinctId
     * @param key the Key
     * @param options the feature flag options containing defaultValue, groups, personProperties, and groupProperties
     */
    @Deprecated(
        message = "Prefer evaluateFlags(distinctId).isEnabled(key). Will be removed in the next major.",
    )
    public fun isFeatureEnabled(
        distinctId: String,
        key: String,
        options: PostHogFeatureFlagOptions,
    ): Boolean {
        @Suppress("DEPRECATION")
        return isFeatureEnabled(
            distinctId,
            key,
            defaultValue = options.defaultValue as? Boolean ?: false,
            groups = options.groups,
            personProperties = options.personProperties,
            groupProperties = options.groupProperties,
        )
    }

    /**
     * Returns the feature flags variant if multi-variant, otherwise whether it is enabled or not
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * @param distinctId the distinctId
     * @param key the Key
     * @param defaultValue the default value if not found
     */
    @Deprecated(
        message = "Prefer evaluateFlags(distinctId).getFlag(key). Will be removed in the next major.",
    )
    public fun getFeatureFlag(
        distinctId: String,
        key: String,
        defaultValue: Any? = null,
        groups: Map<String, String>? = null,
        personProperties: Map<String, Any?>? = null,
        groupProperties: Map<String, Map<String, Any?>>? = null,
    ): Any?

    /**
     * Returns the feature flags variant if multi-variant, otherwise whether it is enabled or not
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * @param distinctId the distinctId
     * @param key the Key
     * @return the feature flag value or null if not found
     */
    @Deprecated(
        message = "Prefer evaluateFlags(distinctId).getFlag(key). Will be removed in the next major.",
    )
    public fun getFeatureFlag(
        distinctId: String,
        key: String,
    ): Any? {
        @Suppress("DEPRECATION")
        return getFeatureFlag(
            distinctId,
            key,
            defaultValue = null,
            groups = null,
            personProperties = null,
            groupProperties = null,
        )
    }

    /**
     * Returns the feature flags variant if multi-variant, otherwise whether it is enabled or not
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * @param distinctId the distinctId
     * @param key the Key
     * @param options the feature flag options containing defaultValue, groups, personProperties, and groupProperties
     */
    @Deprecated(
        message = "Prefer evaluateFlags(distinctId).getFlag(key). Will be removed in the next major.",
    )
    public fun getFeatureFlag(
        distinctId: String,
        key: String,
        options: PostHogFeatureFlagOptions,
    ): Any? {
        @Suppress("DEPRECATION")
        return getFeatureFlag(
            distinctId,
            key,
            defaultValue = options.defaultValue,
            groups = options.groups,
            personProperties = options.personProperties,
            groupProperties = options.groupProperties,
        )
    }

    /**
     * Returns if a feature flag is enabled, the feature flag must be a Boolean
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * @param distinctId the distinctId
     * @param key the Key
     * @param defaultValue the default value if not found
     */
    @Deprecated(
        message = "Prefer evaluateFlags(distinctId).getFlag(key). Will be removed in the next major.",
    )
    public fun getFeatureFlag(
        distinctId: String,
        key: String,
        defaultValue: Any?,
    ): Any? {
        @Suppress("DEPRECATION")
        return getFeatureFlag(
            distinctId,
            key,
            defaultValue = defaultValue,
            groups = null,
            personProperties = null,
            groupProperties = null,
        )
    }

    /**
     * Returns the feature flag payload
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * @param distinctId the distinctId
     * @param key the Key
     * @param defaultValue the default value if not found
     */
    @Deprecated(
        message = "Prefer evaluateFlags(distinctId).getFlagPayload(key). Will be removed in the next major.",
    )
    public fun getFeatureFlagPayload(
        distinctId: String,
        key: String,
        defaultValue: Any? = null,
        groups: Map<String, String>? = null,
        personProperties: Map<String, Any?>? = null,
        groupProperties: Map<String, Map<String, Any?>>? = null,
    ): Any?

    /**
     * Returns the feature flag payload
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * @param distinctId the distinctId
     * @param key the Key
     * @return the feature flag payload or null if not found
     */
    @Deprecated(
        message = "Prefer evaluateFlags(distinctId).getFlagPayload(key). Will be removed in the next major.",
    )
    public fun getFeatureFlagPayload(
        distinctId: String,
        key: String,
    ): Any? {
        @Suppress("DEPRECATION")
        return getFeatureFlagPayload(
            distinctId,
            key,
            defaultValue = null,
            groups = null,
            personProperties = null,
            groupProperties = null,
        )
    }

    /**
     * Returns the feature flag payload
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * @param distinctId the distinctId
     * @param key the Key
     * @param options the feature flag options containing defaultValue, groups, personProperties, and groupProperties
     */
    @Deprecated(
        message = "Prefer evaluateFlags(distinctId).getFlagPayload(key). Will be removed in the next major.",
    )
    public fun getFeatureFlagPayload(
        distinctId: String,
        key: String,
        options: PostHogFeatureFlagOptions,
    ): Any? {
        @Suppress("DEPRECATION")
        return getFeatureFlagPayload(
            distinctId,
            key,
            defaultValue = options.defaultValue,
            groups = options.groups,
            personProperties = options.personProperties,
            groupProperties = options.groupProperties,
        )
    }

    /**
     * Returns the feature flag payload
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * @param distinctId the distinctId
     * @param key the Key
     * @param defaultValue the default value if not found
     */
    @Deprecated(
        message = "Prefer evaluateFlags(distinctId).getFlagPayload(key). Will be removed in the next major.",
    )
    public fun getFeatureFlagPayload(
        distinctId: String,
        key: String,
        defaultValue: Any?,
    ): Any? {
        @Suppress("DEPRECATION")
        return getFeatureFlagPayload(
            distinctId,
            key,
            defaultValue = defaultValue,
            groups = null,
            personProperties = null,
            groupProperties = null,
        )
    }

    /**
     * Returns the feature flag result containing both value and payload.
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * @param distinctId the distinctId
     * @param key the Key
     * @param groups groups for group-based flags
     * @param personProperties person properties for flag evaluation
     * @param groupProperties group properties for flag evaluation
     * @param sendFeatureFlagEvent whether to send the $feature_flag_called event, or null to use config default
     * @return FeatureFlagResult if the flag exists, null otherwise
     */
    @Deprecated(
        message =
            "Prefer evaluateFlags(distinctId) and read flag values + payload from the snapshot. " +
                "Will be removed in the next major.",
    )
    public fun getFeatureFlagResult(
        distinctId: String,
        key: String,
        groups: Map<String, String>? = null,
        personProperties: Map<String, Any?>? = null,
        groupProperties: Map<String, Map<String, Any?>>? = null,
        sendFeatureFlagEvent: Boolean? = null,
    ): FeatureFlagResult?

    /**
     * Returns the feature flag result containing both value and payload.
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * @param distinctId the distinctId
     * @param key the Key
     * @return FeatureFlagResult if the flag exists, null otherwise
     */
    @Deprecated(
        message =
            "Prefer evaluateFlags(distinctId) and read flag values + payload from the snapshot. " +
                "Will be removed in the next major.",
    )
    public fun getFeatureFlagResult(
        distinctId: String,
        key: String,
    ): FeatureFlagResult? {
        @Suppress("DEPRECATION")
        return getFeatureFlagResult(
            distinctId,
            key,
            groups = null,
            personProperties = null,
            groupProperties = null,
            sendFeatureFlagEvent = null,
        )
    }

    /**
     * Returns the feature flag result containing both value and payload.
     * Docs https://posthog.com/docs/feature-flags and https://posthog.com/docs/experiments
     * @param distinctId the distinctId
     * @param key the Key
     * @param options the feature flag result options containing groups, personProperties, groupProperties, and sendFeatureFlagEvent
     * @return FeatureFlagResult if the flag exists, null otherwise
     */
    @Deprecated(
        message =
            "Prefer evaluateFlags(distinctId) and read flag values + payload from the snapshot. " +
                "Will be removed in the next major.",
    )
    public fun getFeatureFlagResult(
        distinctId: String,
        key: String,
        options: PostHogFeatureFlagResultOptions,
    ): FeatureFlagResult? {
        @Suppress("DEPRECATION")
        return getFeatureFlagResult(
            distinctId,
            key,
            groups = options.groups,
            personProperties = options.personProperties,
            groupProperties = options.groupProperties,
            sendFeatureFlagEvent = options.sendFeatureFlagEvent,
        )
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

    /**
     * Evaluate every feature flag for [distinctId] in a single `/flags` round-trip and return a
     * snapshot. Repeat lookups against the snapshot do not make additional network requests, and
     * `is_enabled` / `getFlag` accesses still emit deduped `$feature_flag_called` events.
     *
     * @param distinctId the distinctId
     * @param groups groups for group-based flags
     * @param personProperties person properties for flag evaluation
     * @param groupProperties group properties for flag evaluation
     * @param flagKeys when non-empty, restricts the underlying request to the given keys; this is
     *   distinct from [PostHogFeatureFlagEvaluations.only] which filters in memory after the call
     * @param onlyEvaluateLocally when true, do not fall back to a `/flags` request if local
     *   evaluation cannot resolve every flag
     * @param disableGeoip when true, send `geoip_disable=true` to the server
     */
    @JvmSynthetic
    public fun evaluateFlags(
        distinctId: String,
        groups: Map<String, String>? = null,
        personProperties: Map<String, Any?>? = null,
        groupProperties: Map<String, Map<String, Any?>>? = null,
        flagKeys: List<String>? = null,
        onlyEvaluateLocally: Boolean = false,
        disableGeoip: Boolean = false,
    ): PostHogFeatureFlagEvaluations

    /**
     * Evaluate every feature flag for [distinctId] using the supplied options object.
     * Java-friendly overload that mirrors the canonical [evaluateFlags] entry point.
     */
    public fun evaluateFlags(
        distinctId: String,
        options: PostHogEvaluateFlagsOptions,
    ): PostHogFeatureFlagEvaluations {
        return evaluateFlags(
            distinctId,
            groups = options.groups,
            personProperties = options.personProperties,
            groupProperties = options.groupProperties,
            flagKeys = options.flagKeys,
            onlyEvaluateLocally = options.onlyEvaluateLocally,
            disableGeoip = options.disableGeoip,
        )
    }

    /**
     * Evaluate every feature flag for [distinctId] using default options.
     */
    public fun evaluateFlags(distinctId: String): PostHogFeatureFlagEvaluations {
        return evaluateFlags(
            distinctId,
            groups = null,
            personProperties = null,
            groupProperties = null,
            flagKeys = null,
            onlyEvaluateLocally = false,
            disableGeoip = false,
        )
    }

    /**
     * Reloads feature flag definitions from the server for use with local evaluation.
     * Note that feature flag definitions are automatically fetched on initialization and
     * periodically refreshed, so this method only needs to be called if you want to force
     * an immediate refresh.
     * Docs https://posthog.com/docs/feature-flags/local-evaluation
     */
    public fun reloadFeatureFlags()

    /**
     * Captures an exception
     * Docs https://posthog.com/docs/error-tracking
     * @param exception the exception to capture
     * @param distinctId the distinctId
     * @param properties the exception properties to add to the event
     */
    public fun captureException(
        exception: Throwable,
        distinctId: String? = null,
        properties: Map<String, Any>? = null,
    )

    /**
     * Captures an exception
     * Docs https://posthog.com/docs/error-tracking
     * @param exception the exception to capture
     * @param properties the exception properties to add to the event
     * @param distinctId the distinctId
     */
    public fun captureException(
        exception: Throwable,
        distinctId: String?,
    ) {
        captureException(
            exception,
            distinctId,
            null,
        )
    }

    /**
     * Captures an exception
     * Docs https://posthog.com/docs/error-tracking
     * @param exception the exception to capture
     * @param distinctId the distinctId
     * @param properties the exception properties to add to the event
     */
    public fun captureException(
        exception: Throwable,
        properties: Map<String, Any>,
    ) {
        captureException(
            exception,
            null,
            properties,
        )
    }

    /**
     * Captures an exception
     * Docs https://posthog.com/docs/error-tracking
     * @param exception the exception to capture
     * @param properties the exception properties to add to the event
     * @param distinctId the distinctId
     */
    public fun captureException(exception: Throwable) {
        captureException(
            exception,
            null,
            null,
        )
    }
}
