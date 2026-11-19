package com.posthog.server.internal

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
