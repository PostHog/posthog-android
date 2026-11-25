package com.posthog.server.internal

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

        /**
         * Returns a message naming the field when a list holds more than [MAX_KEYS] strings or a
         * null element (possible from Java callers), or null when the input is valid.
         */
        fun inputError(
            person: List<String>?,
            groups: Map<String, List<String>>?,
        ): String? {
            if (person != null) {
                if (person.size > MAX_KEYS) {
                    return "person_property_keys_known_absent allows at most $MAX_KEYS keys, got ${person.size}"
                }
                if (person.containsNullElement()) {
                    return "person_property_keys_known_absent must not contain null keys"
                }
            }
            groups?.forEach { (groupType, keys) ->
                if (keys.size > MAX_KEYS) {
                    return "group_property_keys_known_absent allows at most $MAX_KEYS keys per group type, " +
                        "got ${keys.size} for group type '$groupType'"
                }
                if (keys.containsNullElement()) {
                    return "group_property_keys_known_absent must not contain null keys, found one for group type '$groupType'"
                }
            }
            return null
        }

        private fun List<String>.containsNullElement(): Boolean = (this as List<String?>).contains(null)

        /** Returns null when nothing is listed. Call [inputError] first. */
        fun of(
            person: List<String>?,
            groups: Map<String, List<String>>?,
        ): KnownAbsentPropertyKeys? {
            val personKeys = person.orEmpty().toCollection(LinkedHashSet())
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
 * from a listing. A listed person key with the `__posthog_person_metadata__` prefix is still sent to
 * `/flags` unchanged, but never resolves a filter locally, as the flags service ignores it.
 */
internal class KnownAbsentKeyScope private constructor(
    private val keys: Set<String>,
    private val filterType: String,
) {
    fun isKnownAbsent(property: FlagProperty): Boolean =
        property.rawType == filterType &&
            property.key in keys &&
            !(filterType == PERSON_FILTER_TYPE && property.key.startsWith(PERSON_METADATA_KEY_PREFIX))

    internal companion object {
        private const val PERSON_FILTER_TYPE = "person"
        private const val GROUP_FILTER_TYPE = "group"

        // Known absence never hides a persons-table field such as `created_at`.
        private const val PERSON_METADATA_KEY_PREFIX = "__posthog_person_metadata__"

        val NONE: KnownAbsentKeyScope = KnownAbsentKeyScope(emptySet(), PERSON_FILTER_TYPE)

        fun person(keys: Set<String>): KnownAbsentKeyScope = KnownAbsentKeyScope(keys, PERSON_FILTER_TYPE)

        fun group(keys: Set<String>): KnownAbsentKeyScope = KnownAbsentKeyScope(keys, GROUP_FILTER_TYPE)
    }
}
