package com.posthog.server

/**
 * Provides an ergonomic interface when providing options for capturing events
 * This is mainly meant to be used from Java, as Kotlin can use named parameters.
 * @see <a href="https://posthog.com/docs/product-analytics/capture-events">Documentation: Capturing events</a>
 */
public class PostHogSendFeatureFlagOptions private constructor(
    public val onlyEvaluateLocally: Boolean = false,
    public val personProperties: Map<String, String>?,
    public val groupProperties: Map<String, String>?,
) {
    public class Builder {
        public var onlyEvaluateLocally: Boolean = false
        public var personProperties: MutableMap<String, String>? = null
        public var groupProperties: MutableMap<String, String>? = null

        /**
         * Sets whether to only evaluate the feature flags locally.
         */
        public fun onlyEvaluateLocally(onlyEvaluateLocally: Boolean): Builder {
            this.onlyEvaluateLocally = onlyEvaluateLocally
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

        public fun build(): PostHogSendFeatureFlagOptions =
            PostHogSendFeatureFlagOptions(
                onlyEvaluateLocally = onlyEvaluateLocally,
                personProperties = personProperties,
                groupProperties = groupProperties,
            )
    }

    public companion object {
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}
