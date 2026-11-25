package com.posthog.server

/**
 * Java-friendly options builder for [PostHogInterface.evaluateFlags].
 *
 * Kotlin callers should prefer named arguments on the method itself.
 *
 * @property groups Groups for group-based flags, keyed by group type.
 * @property personProperties Person properties to use for flag evaluation.
 * @property groupProperties Group properties to use for flag evaluation, keyed by group type.
 * @property flagKeys Optional list of flag keys to evaluate, scoping both local evaluation and the
 *   remote request. When null, all matching flags are evaluated. An empty list returns an empty
 *   snapshot without consulting caches, local definitions, or `/flags`. Keys with no local
 *   definition are included in the `/flags` fallback. After a clean response also omits a key, later calls suppress
 *   its fallback until the next successful definitions refresh, bounding deleted keys and typos to
 *   at most one clean probe per refresh interval.
 * @property onlyEvaluateLocally Whether the snapshot must hold exactly what local evaluation
 *   resolved: no `/flags` fallback, no cached remote values, unresolved flags absent.
 * @property disableGeoip Whether to send `geoip_disable=true` during remote evaluation.
 * @property personPropertyKeysKnownAbsent Person property keys the caller knows are absent for
 *   this evaluation. A listed key is absent even when [personProperties] holds a value for it, or
 *   when the SDK would add one itself, such as `distinct_id`. A listed key applies only to filters
 *   of type `person`, never to other filter types such as `person_metadata`. Local evaluation
 *   resolves such a filter on a listed key without remote fallback: `is_not_set`, `is_not`, `not_icontains`,
 *   `not_starts_with`, `not_ends_with` and `not_regex` match, and every other supported operator
 *   does not. A filter that local evaluation does not support still falls back to `/flags`. Keys
 *   starting with `__posthog_person_metadata__` never resolve a filter locally and are sent to
 *   `/flags` unchanged, where they are ignored. The keys are sent to `/flags` when the
 *   evaluation falls back to it. At most 1000 keys, counting duplicates, and no null keys. Over the
 *   limit or with a null key the SDK logs a warning, evaluates nothing and sends no request: the
 *   snapshot or flag lookup returns its unavailable result (empty snapshot, default value, or null).
 *   Lists are never truncated.
 * @property groupPropertyKeysKnownAbsent Group property keys the caller knows are absent for this
 *   evaluation, keyed by group type. A key applies only to filters of type `group` in flags
 *   aggregated on its own group type and wins over any value in [groupProperties] for that type. Otherwise this follows [personPropertyKeysKnownAbsent],
 *   with the limit of 1000 keys applying to each group type. A null group type is rejected like a null key.
 *
 * Known-absent keys are not available on [PostHogCaptureOptions]; evaluate a snapshot with these
 * options and pass it to `capture` instead.
 */
public class PostHogEvaluateFlagsOptions private constructor(
    public val groups: Map<String, String>?,
    public val personProperties: Map<String, Any?>?,
    public val groupProperties: Map<String, Map<String, Any?>>?,
    public val flagKeys: List<String>?,
    public val onlyEvaluateLocally: Boolean,
    public val disableGeoip: Boolean,
    public val personPropertyKeysKnownAbsent: List<String>?,
    public val groupPropertyKeysKnownAbsent: Map<String, List<String>>?,
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

        /** Person property keys known to be absent for this evaluation. */
        public var personPropertyKeysKnownAbsent: MutableList<String>? = null

        /** Group property keys known to be absent for this evaluation, keyed by group type. */
        public var groupPropertyKeysKnownAbsent: MutableMap<String, MutableList<String>>? = null

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
            this.groups = groups.putBuilderValue(type, key)
            return this
        }

        /**
         * Appends multiple groups for group-based flag evaluation.
         *
         * @param groups Groups to merge, keyed by group type.
         * @return This builder.
         */
        public fun groups(groups: Map<String, String>): Builder {
            this.groups = this.groups.putBuilderValues(groups)
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
            this.personProperties = personProperties.putBuilderValue(key, value)
            return this
        }

        /**
         * Appends multiple person properties for flag evaluation.
         *
         * @param personProperties Person properties to merge.
         * @return This builder.
         */
        public fun personProperties(personProperties: Map<String, Any?>): Builder {
            this.personProperties = this.personProperties.putBuilderValues(personProperties)
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
            val existing = groupProperties?.get(type)?.toMutableMap().putBuilderValue(key, value)
            this.groupProperties = groupProperties.putBuilderValue(type, existing)
            return this
        }

        /**
         * Appends multiple group properties for flag evaluation.
         *
         * @param groupProperties Group properties to merge, keyed by group type.
         * @return This builder.
         */
        public fun groupProperties(groupProperties: Map<String, Map<String, Any?>>): Builder {
            this.groupProperties = this.groupProperties.mergeBuilderGroupProperties(groupProperties)
            return this
        }

        /**
         * Restricts the underlying `/flags` request to the given keys.
         *
         * This scopes what the server computes; the snapshot's `only(...)` helper, by contrast,
         * filters in memory.
         *
         * @param flagKeys Feature flag keys to request. An empty list returns an empty snapshot
         *   without performing evaluation work.
         * @return This builder.
         */
        public fun flagKeys(flagKeys: List<String>): Builder {
            this.flagKeys = this.flagKeys.addBuilderValues(flagKeys)
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
         * Appends person property keys known to be absent for this evaluation. A listed key is
         * treated as absent even when [personProperties] holds a value for it.
         *
         * @param keys Person property keys. Duplicates count toward the limit of 1000.
         * @return This builder.
         */
        public fun personPropertyKeysKnownAbsent(keys: Collection<String>): Builder {
            this.personPropertyKeysKnownAbsent = this.personPropertyKeysKnownAbsent.addBuilderValues(keys)
            return this
        }

        /**
         * Appends group property keys known to be absent for one group type in this evaluation.
         *
         * @param groupType Group type, for example `company`.
         * @param keys Group property keys. Duplicates count toward the limit of 1000 per group type.
         * @return This builder.
         */
        public fun groupPropertyKeysKnownAbsent(
            groupType: String,
            keys: Collection<String>,
        ): Builder {
            this.groupPropertyKeysKnownAbsent = this.groupPropertyKeysKnownAbsent.addBuilderGroupKeys(groupType, keys)
            return this
        }

        /**
         * Appends group property keys known to be absent for this evaluation, keyed by group type.
         *
         * @param keys Group property keys to merge, keyed by group type.
         * @return This builder.
         */
        public fun groupPropertyKeysKnownAbsent(keys: Map<String, Collection<String>>): Builder {
            keys.forEach { (groupType, groupKeys) -> groupPropertyKeysKnownAbsent(groupType, groupKeys) }
            return this
        }

        /**
         * Builds an immutable [PostHogEvaluateFlagsOptions] instance.
         *
         * @return Evaluate-flags options containing the accumulated values.
         */
        public fun build(): PostHogEvaluateFlagsOptions =
            PostHogEvaluateFlagsOptions(
                groups.toBuilderMapSnapshot(),
                personProperties.toBuilderMapSnapshot(),
                groupProperties.toBuilderGroupPropertiesSnapshot(),
                flagKeys.toBuilderListSnapshot(),
                onlyEvaluateLocally,
                disableGeoip,
                personPropertyKeysKnownAbsent.toBuilderListSnapshot(),
                groupPropertyKeysKnownAbsent.toBuilderGroupKeysSnapshot(),
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
