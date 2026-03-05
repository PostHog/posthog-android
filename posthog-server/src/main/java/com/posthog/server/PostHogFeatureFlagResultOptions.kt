package com.posthog.server

/**
 * Provides an ergonomic interface when providing options for getFeatureFlagResult.
 * This is mainly meant to be used from Java, as Kotlin can use named parameters.
 */
public class PostHogFeatureFlagResultOptions private constructor(
    public val groups: Map<String, String>?,
    public val personProperties: Map<String, Any?>?,
    public val groupProperties: Map<String, Map<String, Any?>>?,
    public val sendFeatureFlagEvent: Boolean?,
) {
    public class Builder {
        public var groups: MutableMap<String, String>? = null
        public var personProperties: MutableMap<String, Any?>? = null
        public var groupProperties: MutableMap<String, MutableMap<String, Any?>>? = null
        public var sendFeatureFlagEvent: Boolean? = null

        /**
         * Sets whether to send the $feature_flag_called event when the flag is evaluated.
         * When null, uses the default behavior from PostHogConfig.sendFeatureFlagEvent.
         */
        public fun sendFeatureFlagEvent(sendFeatureFlagEvent: Boolean?): Builder {
            this.sendFeatureFlagEvent = sendFeatureFlagEvent
            return this
        }

        /**
         * Add a single custom property to the capture options
         */
        public fun group(
            key: String,
            propValue: String,
        ): Builder {
            groups =
                (groups ?: mutableMapOf()).apply {
                    put(key, propValue)
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
            propValue: Any?,
        ): Builder {
            personProperties =
                (personProperties ?: mutableMapOf()).apply {
                    put(key, propValue)
                }
            return this
        }

        /**
         * Appends multiple user properties to the capture options.
         * @see <a href="https://posthog.com/docs/product-analytics/user-properties">Documentation: User Properties</a>
         */
        public fun personProperties(userProperties: Map<String, Any?>): Builder {
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
            group: String,
            key: String,
            propValue: Any?,
        ): Builder {
            groupProperties =
                (groupProperties ?: mutableMapOf()).apply {
                    getOrPut(group) { mutableMapOf() }[key] = propValue
                }
            return this
        }

        /**
         * Appends multiple user properties (set once) to the capture options.
         * @see <a href="https://posthog.com/docs/product-analytics/user-properties">Documentation: User Properties</a>
         */
        public fun groupProperties(groupProperties: Map<String, Map<String, Any?>>): Builder {
            this.groupProperties =
                (this.groupProperties ?: mutableMapOf()).apply {
                    groupProperties.forEach { (group, properties) ->
                        getOrPut(group) { mutableMapOf() }.putAll(properties)
                    }
                }
            return this
        }

        public fun build(): PostHogFeatureFlagResultOptions =
            PostHogFeatureFlagResultOptions(
                groups = groups,
                personProperties = personProperties,
                groupProperties = groupProperties,
                sendFeatureFlagEvent = sendFeatureFlagEvent,
            )
    }

    public companion object {
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}
