package com.posthog.server

import java.time.Instant
import java.util.Date

/**
 * Java-friendly options for capturing events.
 *
 * Kotlin callers can usually use named parameters on [PostHogInterface.capture] instead.
 *
 * @property properties Custom event properties.
 * @property userProperties Person properties to set as `$set` with the capture.
 * @property userPropertiesSetOnce Person properties to set as `$set_once` with the capture.
 * @property groups Group assignments to attach as `$groups`.
 * @property timestamp Event timestamp. When null, the SDK uses the current time.
 * @property appendFeatureFlags Whether to enrich the event by evaluating and attaching feature flag
 *   properties. Prefer passing a pre-evaluated [flags] snapshot for new code.
 * @property flags Optional snapshot returned by [PostHogInterface.evaluateFlags] to attach feature
 *   flag properties without another `/flags` request.
 * @see <a href="https://posthog.com/docs/product-analytics/capture-events">Documentation: Capturing events</a>
 */
public class PostHogCaptureOptions private constructor(
    public val properties: Map<String, Any>?,
    public val userProperties: Map<String, Any>?,
    public val userPropertiesSetOnce: Map<String, Any>?,
    public val groups: Map<String, String>?,
    public val timestamp: Date? = null,
    public val appendFeatureFlags: Boolean = false,
    public val flags: PostHogFeatureFlagEvaluations? = null,
) {
    /**
     * Mutable builder for [PostHogCaptureOptions].
     */
    public class Builder {
        /** Custom event properties accumulated for the capture. */
        public var properties: MutableMap<String, Any>? = null

        /** Person properties to set as `$set`. */
        public var userProperties: MutableMap<String, Any>? = null

        /** Person properties to set as `$set_once`. */
        public var userPropertiesSetOnce: MutableMap<String, Any>? = null

        /** Group assignments to attach as `$groups`. */
        public var groups: MutableMap<String, String>? = null

        /** Event timestamp override. */
        public var timestamp: Date? = null

        /** Whether to evaluate and append feature flag properties during capture. */
        public var appendFeatureFlags: Boolean = false

        /** Optional pre-evaluated feature flag snapshot to append during capture. */
        public var flags: PostHogFeatureFlagEvaluations? = null

        /**
         * Add a single custom property to the capture options.
         *
         * @param key Property name.
         * @param value Property value.
         * @return This builder.
         */
        public fun property(
            key: String,
            value: Any,
        ): Builder {
            properties = properties.putBuilderValue(key, value)
            return this
        }

        /**
         * Appends multiple custom properties to the capture options.
         *
         * @param properties Properties to merge into the capture options.
         * @return This builder.
         */
        public fun properties(properties: Map<String, Any>): Builder {
            this.properties = this.properties.putBuilderValues(properties)
            return this
        }

        /**
         * Adds a single user property to the capture options.
         *
         * @param key User property name.
         * @param value User property value.
         * @return This builder.
         * @see <a href="https://posthog.com/docs/product-analytics/user-properties">Documentation: User Properties</a>
         */
        public fun userProperty(
            key: String,
            value: Any,
        ): Builder {
            this.userProperties = this.userProperties.putBuilderValue(key, value)
            return this
        }

        /**
         * Appends multiple user properties to the capture options.
         *
         * @param userProperties User properties to merge.
         * @return This builder.
         * @see <a href="https://posthog.com/docs/product-analytics/user-properties">Documentation: User Properties</a>
         */
        public fun userProperties(userProperties: Map<String, Any>): Builder {
            this.userProperties = this.userProperties.putBuilderValues(userProperties)
            return this
        }

        /**
         * Adds a single user property (set once) to the capture options.
         *
         * @param key User property name.
         * @param value User property value.
         * @return This builder.
         * @see <a href="https://posthog.com/docs/product-analytics/user-properties">Documentation: User Properties</a>
         */
        public fun userPropertySetOnce(
            key: String,
            value: Any,
        ): Builder {
            this.userPropertiesSetOnce = this.userPropertiesSetOnce.putBuilderValue(key, value)
            return this
        }

        /**
         * Appends multiple user properties (set once) to the capture options.
         *
         * @param userPropertiesSetOnce User properties to merge as `$set_once`.
         * @return This builder.
         * @see <a href="https://posthog.com/docs/product-analytics/user-properties">Documentation: User Properties</a>
         */
        public fun userPropertiesSetOnce(userPropertiesSetOnce: Map<String, Any>): Builder {
            this.userPropertiesSetOnce = this.userPropertiesSetOnce.putBuilderValues(userPropertiesSetOnce)
            return this
        }

        /**
         * Adds a single group to the capture options.
         *
         * @param type Group type, for example `company`.
         * @param key Group key or identifier.
         * @return This builder.
         * @see <a href="https://posthog.com/docs/product-analytics/group-analytics">Documentation: Group Analytics</a>
         */
        public fun group(
            type: String,
            key: String,
        ): Builder {
            this.groups = this.groups.putBuilderValue(type, key)
            return this
        }

        /**
         * Appends multiple groups to the capture options.
         *
         * @param groups Groups to merge, keyed by group type.
         * @return This builder.
         * @see <a href="https://posthog.com/docs/product-analytics/group-analytics">Documentation: Group Analytics</a>
         */
        public fun groups(groups: Map<String, String>): Builder {
            this.groups = this.groups.putBuilderValues(groups)
            return this
        }

        /**
         * Override the timestamp for the event.
         *
         * @param date Event timestamp.
         * @return This builder.
         * @see <a href="https://posthog.com/docs/data/timestamps">Documentation: Timestamps</a>
         */
        public fun timestamp(date: Date): Builder {
            this.timestamp = date
            return this
        }

        /**
         * Override the timestamp for the event.
         *
         * @param epochMillis Event timestamp in milliseconds since the Unix epoch.
         * @return This builder.
         * @see <a href="https://posthog.com/docs/data/timestamps">Documentation: Timestamps</a>
         */
        public fun timestamp(epochMillis: Long): Builder {
            this.timestamp = Date(epochMillis)
            return this
        }

        /**
         * Override the timestamp for the event.
         *
         * @param instant Event timestamp.
         * @return This builder.
         * @see <a href="https://posthog.com/docs/data/timestamps">Documentation: Timestamps</a>
         */
        public fun timestamp(instant: Instant): Builder {
            this.timestamp = Date(instant.toEpochMilli())
            return this
        }

        /**
         * When true, enriches the event with all evaluated feature flags.
         *
         * Adds `$feature/{flagName}` properties for each flag and `$active_feature_flags` array
         * containing names of all truthy flags. Prefer evaluating flags once and passing [flags]
         * when you need to attach flag information to a capture.
         *
         * @param appendFeatureFlags Whether to evaluate and attach feature flag properties.
         * @return This builder.
         * @see <a href="https://posthog.com/docs/feature-flags">Documentation: Feature Flags</a>
         */
        public fun appendFeatureFlags(appendFeatureFlags: Boolean): Builder {
            this.appendFeatureFlags = appendFeatureFlags
            return this
        }

        /**
         * Attach a snapshot returned by [PostHogInterface.evaluateFlags].
         *
         * The capture event will be enriched with `$feature/<key>` properties and
         * `$active_feature_flags` from the snapshot without making another `/flags` request.
         * Mutually exclusive with [appendFeatureFlags]; the snapshot wins when both are supplied.
         *
         * @param flags Pre-evaluated feature flag snapshot, or null to clear it.
         * @return This builder.
         */
        public fun flags(flags: PostHogFeatureFlagEvaluations?): Builder {
            this.flags = flags
            return this
        }

        /**
         * Builds an immutable [PostHogCaptureOptions] instance.
         *
         * @return Capture options containing the accumulated values.
         */
        public fun build(): PostHogCaptureOptions =
            PostHogCaptureOptions(
                properties,
                userProperties,
                userPropertiesSetOnce,
                groups,
                timestamp,
                appendFeatureFlags,
                flags,
            )
    }

    public companion object {
        /**
         * Creates a new Java-friendly capture options builder.
         *
         * @return A new [Builder].
         */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}
