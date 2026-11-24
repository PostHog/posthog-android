package com.posthog.server.internal

import com.posthog.internal.FlagDefinition
import com.posthog.internal.FlagProperty

/**
 * Property keys the caller knows are absent for one evaluation. A listed key is absent even when
 * the supplied properties hold a value for it. Empty inputs are represented by no instance at all,
 * so evaluations without a listing keep their existing cache keys and `/flags` bodies.
 */
internal data class KnownAbsentPropertyKeys(
    val person: Set<String>,
    val groups: Map<String, Set<String>>,
) {
    fun forGroupType(groupType: String): Set<String> = groups[groupType].orEmpty()

    internal companion object {
        /** The `/flags` limit per array, counted with duplicates and per group type. */
        internal const val MAX_KEYS: Int = 1000

        // Known absence never hides a persons-table field such as `created_at`.
        private const val PERSON_METADATA_KEY_PREFIX = "__posthog_person_metadata__"

        /**
         * Returns a message naming the field when a list holds more than [MAX_KEYS] strings,
         * or null when the input is within the limit.
         */
        fun limitError(
            person: List<String>?,
            groups: Map<String, List<String>>?,
        ): String? {
            if (person != null && person.size > MAX_KEYS) {
                return "person_property_keys_known_absent allows at most $MAX_KEYS keys, got ${person.size}"
            }
            groups?.forEach { (groupType, keys) ->
                if (keys.size > MAX_KEYS) {
                    return "group_property_keys_known_absent allows at most $MAX_KEYS keys per group type, " +
                        "got ${keys.size} for group type '$groupType'"
                }
            }
            return null
        }

        /** Returns null when nothing is listed. Call [limitError] first. */
        fun of(
            person: List<String>?,
            groups: Map<String, List<String>>?,
        ): KnownAbsentPropertyKeys? {
            val personKeys = person.orEmpty().filterNotTo(LinkedHashSet()) { it.startsWith(PERSON_METADATA_KEY_PREFIX) }
            val groupKeys =
                groups.orEmpty()
                    .mapValues { (_, keys) -> keys.toCollection(LinkedHashSet()) }
                    .filterValues { it.isNotEmpty() }
            if (personKeys.isEmpty() && groupKeys.isEmpty()) {
                return null
            }
            return KnownAbsentPropertyKeys(personKeys, groupKeys)
        }
    }
}

/**
 * The known-absent keys that apply while evaluating one flag. A person listing applies only to
 * filters of type `person`, and a group listing only to filters of type `group` in a flag
 * aggregated on that group type. Other filter types, such as `person_metadata`, never resolve
 * from a listing.
 */
internal class KnownAbsentKeyScope private constructor(
    private val keys: Set<String>,
    private val filterType: String,
    private val aggregationGroupTypeIndex: Int?,
) {
    fun isKnownAbsent(property: FlagProperty): Boolean = property.rawType == filterType && property.key in keys

    /** A dependency aggregated differently from the flag that references it gets no keys. */
    fun forDependency(dependency: FlagDefinition): KnownAbsentKeyScope =
        if (dependency.filters.aggregationGroupTypeIndex == aggregationGroupTypeIndex) this else NONE

    internal companion object {
        private const val PERSON_FILTER_TYPE = "person"
        private const val GROUP_FILTER_TYPE = "group"

        val NONE: KnownAbsentKeyScope = KnownAbsentKeyScope(emptySet(), PERSON_FILTER_TYPE, null)

        fun person(keys: Set<String>): KnownAbsentKeyScope = KnownAbsentKeyScope(keys, PERSON_FILTER_TYPE, null)

        fun group(
            keys: Set<String>,
            aggregationGroupTypeIndex: Int,
        ): KnownAbsentKeyScope = KnownAbsentKeyScope(keys, GROUP_FILTER_TYPE, aggregationGroupTypeIndex)
    }
}
