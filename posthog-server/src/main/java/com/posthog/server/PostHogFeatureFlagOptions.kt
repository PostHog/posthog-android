package com.posthog.server

/**
 * Provides an ergonomic interface when providing options for capturing events
 * This is mainly meant to be used from Java, as Kotlin can use named parameters.
 */
public class PostHogFeatureFlagOptions private constructor(
    public val defaultValue: Any?,
    public val groups: Map<String, String>?,
    public val personProperties: Map<String, String>?,
    public val groupProperties: Map<String, String>?,
) {
    public class Builder {
        public var defaultValue: Any? = null
        public var groups: MutableMap<String, String>? = null
        public var personProperties: MutableMap<String, String>? = null
        public var groupProperties: MutableMap<String, String>? = null

        /**
         * Sets the default value to return if the feature flag is not found or not enabled
         */
        public fun defaultValue(defaultValue: Any?): Builder {
            this.defaultValue = defaultValue
            return this
        }

        /**
         * Add a single custom property to the capture options
         */
        public fun group(
            key: String,
            value: String,
        ): Builder {
            groups =
                (groups ?: mutableMapOf()).apply {
                    put(key, value)
                }
            return this
        }

        /**
         * Appends multiple groups to the feature flag options
         */
        public fun groups(groups: Map<String, String>): Builder {
            this.groups =
                (this.groups ?: mutableMapOf()).apply {
                    putAll(groups)
                }
            return this
        }

        /**
         * Adds a single user property to the capture options
         * @see <a href="https://posthog.com/docs/product-analytics/user-properties">Documentation: User Properties</a>
         */
        public fun personProperty(
            key: String,
            value: String,
        ): Builder {
            personProperties =
                (personProperties ?: mutableMapOf()).apply {
                    put(key, value)
                }
            return this
        }

        /**
         * Appends multiple user properties to the capture options.
         * @see <a href="https://posthog.com/docs/product-analytics/user-properties">Documentation: User Properties</a>
         */
        public fun personProperties(userProperties: Map<String, String>): Builder {
            this.personProperties =
                (this.personProperties ?: mutableMapOf()).apply {
                    putAll(userProperties)
                }
            return this
        }

        /**
         * Adds a single user property (set once) to the capture options.
         * @see <a href="https://posthog.com/docs/product-analytics/user-properties">Documentation: User Properties</a>
         */
        public fun groupProperty(
            key: String,
            value: String,
        ): Builder {
            groupProperties =
                (groupProperties ?: mutableMapOf()).apply {
                    put(key, value)
                }
            return this
        }

        /**
         * Appends multiple user properties (set once) to the capture options.
         * @see <a href="https://posthog.com/docs/product-analytics/user-properties">Documentation: User Properties</a>
         */
        public fun groupProperties(groupProperties: Map<String, String>): Builder {
            this.groupProperties =
                (this.groupProperties ?: mutableMapOf()).apply {
                    putAll(groupProperties)
                }
            return this
        }

        public fun build(): PostHogFeatureFlagOptions =
            PostHogFeatureFlagOptions(
                defaultValue = defaultValue,
                groups = groups,
                personProperties = personProperties,
                groupProperties = groupProperties,
            )
    }

    public companion object {
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}
