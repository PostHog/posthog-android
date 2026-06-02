package com.posthog.server

/**
 * Java-friendly options for deprecated one-off feature flag lookup methods.
 *
 * Prefer [PostHogInterface.evaluateFlags] for new code so multiple flag reads can share one
 * evaluation snapshot.
 *
 * @property defaultValue Value returned when the flag is missing or unavailable.
 * @property groups Groups for group-based flags, keyed by group type.
 * @property personProperties Person properties to use for flag evaluation.
 * @property groupProperties Group properties to use for flag evaluation, keyed by group type.
 */
public class PostHogFeatureFlagOptions private constructor(
    public val defaultValue: Any?,
    public val groups: Map<String, String>?,
    public val personProperties: Map<String, Any?>?,
    public val groupProperties: Map<String, Map<String, Any?>>?,
) {
    /**
     * Mutable builder for [PostHogFeatureFlagOptions].
     */
    public class Builder {
        /** Value returned when the flag is missing or unavailable. */
        public var defaultValue: Any? = null

        /** Groups for group-based flags, keyed by group type. */
        public var groups: MutableMap<String, String>? = null

        /** Person properties used for flag evaluation. */
        public var personProperties: MutableMap<String, Any?>? = null

        /** Group properties used for flag evaluation, keyed by group type. */
        public var groupProperties: MutableMap<String, MutableMap<String, Any?>>? = null

        /**
         * Sets the default value to return if the feature flag is not found or unavailable.
         *
         * @param defaultValue Default value for the lookup.
         * @return This builder.
         */
        public fun defaultValue(defaultValue: Any?): Builder {
            this.defaultValue = defaultValue
            return this
        }

        /**
         * Adds a single group for group-based flag evaluation.
         *
         * @param key Group type, for example `company`.
         * @param propValue Group key or identifier.
         * @return This builder.
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
         * Appends multiple groups for group-based flag evaluation.
         *
         * @param groups Groups to merge, keyed by group type.
         * @return This builder.
         */
        public fun groups(groups: Map<String, String>): Builder {
            this.groups =
                (this.groups ?: mutableMapOf()).apply {
                    putAll(groups)
                }
            return this
        }

        /**
         * Adds a single person property for flag evaluation.
         *
         * @param key Person property name.
         * @param propValue Person property value.
         * @return This builder.
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
         * Appends multiple person properties for flag evaluation.
         *
         * @param userProperties Person properties to merge.
         * @return This builder.
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
         * Adds a single group property for flag evaluation.
         *
         * @param group Group type, for example `company`.
         * @param key Group property name.
         * @param propValue Group property value.
         * @return This builder.
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
         * Appends multiple group properties for flag evaluation.
         *
         * @param groupProperties Group properties to merge, keyed by group type.
         * @return This builder.
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

        /**
         * Builds an immutable [PostHogFeatureFlagOptions] instance.
         *
         * @return Feature-flag lookup options containing the accumulated values.
         */
        public fun build(): PostHogFeatureFlagOptions =
            PostHogFeatureFlagOptions(
                defaultValue = defaultValue,
                groups = groups,
                personProperties = personProperties,
                groupProperties = groupProperties,
            )
    }

    public companion object {
        /**
         * Creates a new Java-friendly feature flag options builder.
         *
         * @return A new [Builder].
         */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}
