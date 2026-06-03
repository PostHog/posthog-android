package com.posthog.server

/**
 * Java-friendly options builder for [PostHogInterface.evaluateFlags].
 *
 * Kotlin callers should prefer named arguments on the method itself.
 *
 * @property groups Groups for group-based flags, keyed by group type.
 * @property personProperties Person properties to use for flag evaluation.
 * @property groupProperties Group properties to use for flag evaluation, keyed by group type.
 * @property flagKeys Optional list of flag keys to evaluate. When null, all matching flags are evaluated.
 * @property onlyEvaluateLocally Whether to skip the remote `/flags` fallback if local evaluation is inconclusive.
 * @property disableGeoip Whether to send `geoip_disable=true` during remote evaluation.
 */
public class PostHogEvaluateFlagsOptions private constructor(
    public val groups: Map<String, String>?,
    public val personProperties: Map<String, Any?>?,
    public val groupProperties: Map<String, Map<String, Any?>>?,
    public val flagKeys: List<String>?,
    public val onlyEvaluateLocally: Boolean,
    public val disableGeoip: Boolean,
) {
    /**
     * Mutable builder for [PostHogEvaluateFlagsOptions].
     */
    public class Builder {
        /** Groups for group-based flags, keyed by group type. */
        public var groups: MutableMap<String, String>? = null

        /** Person properties used for flag evaluation. */
        public var personProperties: MutableMap<String, Any?>? = null

        /** Group properties used for flag evaluation, keyed by group type. */
        public var groupProperties: MutableMap<String, Map<String, Any?>>? = null

        /** Flag keys to request from the server. */
        public var flagKeys: MutableList<String>? = null

        /** Whether to skip the remote fallback if local evaluation cannot resolve a flag. */
        public var onlyEvaluateLocally: Boolean = false

        /** Whether to disable GeoIP property lookup for the remote evaluation request. */
        public var disableGeoip: Boolean = false

        /**
         * Adds a single group for group-based flag evaluation.
         *
         * @param type Group type, for example `company`.
         * @param key Group key or identifier.
         * @return This builder.
         */
        public fun group(
            type: String,
            key: String,
        ): Builder {
            this.groups = (groups ?: mutableMapOf()).apply { put(type, key) }
            return this
        }

        /**
         * Appends multiple groups for group-based flag evaluation.
         *
         * @param groups Groups to merge, keyed by group type.
         * @return This builder.
         */
        public fun groups(groups: Map<String, String>): Builder {
            this.groups = (this.groups ?: mutableMapOf()).apply { putAll(groups) }
            return this
        }

        /**
         * Adds a single person property for flag evaluation.
         *
         * @param key Person property name.
         * @param value Person property value.
         * @return This builder.
         */
        public fun personProperty(
            key: String,
            value: Any?,
        ): Builder {
            this.personProperties = (personProperties ?: mutableMapOf()).apply { put(key, value) }
            return this
        }

        /**
         * Appends multiple person properties for flag evaluation.
         *
         * @param personProperties Person properties to merge.
         * @return This builder.
         */
        public fun personProperties(personProperties: Map<String, Any?>): Builder {
            this.personProperties = (this.personProperties ?: mutableMapOf()).apply { putAll(personProperties) }
            return this
        }

        /**
         * Adds a single group property for flag evaluation.
         *
         * @param type Group type, for example `company`.
         * @param key Group property name.
         * @param value Group property value.
         * @return This builder.
         */
        public fun groupProperty(
            type: String,
            key: String,
            value: Any?,
        ): Builder {
            val existing = groupProperties?.get(type)?.toMutableMap() ?: mutableMapOf()
            existing[key] = value
            this.groupProperties = (groupProperties ?: mutableMapOf()).apply { put(type, existing) }
            return this
        }

        /**
         * Appends multiple group properties for flag evaluation.
         *
         * @param groupProperties Group properties to merge, keyed by group type.
         * @return This builder.
         */
        public fun groupProperties(groupProperties: Map<String, Map<String, Any?>>): Builder {
            this.groupProperties = (this.groupProperties ?: mutableMapOf()).apply { putAll(groupProperties) }
            return this
        }

        /**
         * Restricts the underlying `/flags` request to the given keys.
         *
         * This scopes what the server computes; the snapshot's `only(...)` helper, by contrast,
         * filters in memory.
         *
         * @param flagKeys Feature flag keys to request.
         * @return This builder.
         */
        public fun flagKeys(flagKeys: List<String>): Builder {
            this.flagKeys = (this.flagKeys ?: mutableListOf()).apply { addAll(flagKeys) }
            return this
        }

        /**
         * Sets whether the SDK should skip the remote fallback when local evaluation is inconclusive.
         *
         * @param onlyEvaluateLocally true to evaluate only locally; false to allow remote fallback.
         * @return This builder.
         */
        public fun onlyEvaluateLocally(onlyEvaluateLocally: Boolean): Builder {
            this.onlyEvaluateLocally = onlyEvaluateLocally
            return this
        }

        /**
         * Sets whether the remote evaluation request should disable GeoIP property lookup.
         *
         * @param disableGeoip true to send `geoip_disable=true`.
         * @return This builder.
         */
        public fun disableGeoip(disableGeoip: Boolean): Builder {
            this.disableGeoip = disableGeoip
            return this
        }

        /**
         * Builds an immutable [PostHogEvaluateFlagsOptions] instance.
         *
         * @return Evaluate-flags options containing the accumulated values.
         */
        public fun build(): PostHogEvaluateFlagsOptions =
            PostHogEvaluateFlagsOptions(
                groups,
                personProperties,
                groupProperties,
                flagKeys,
                onlyEvaluateLocally,
                disableGeoip,
            )
    }

    public companion object {
        /**
         * Creates a new Java-friendly evaluate-flags options builder.
         *
         * @return A new [Builder].
         */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}
