package com.posthog.server

/**
 * Java-friendly options builder for [PostHogInterface.evaluateFlags]. Kotlin callers should prefer
 * named arguments on the method itself.
 */
public class PostHogEvaluateFlagsOptions private constructor(
    public val groups: Map<String, String>?,
    public val personProperties: Map<String, Any?>?,
    public val groupProperties: Map<String, Map<String, Any?>>?,
    public val flagKeys: List<String>?,
    public val onlyEvaluateLocally: Boolean,
    public val disableGeoip: Boolean,
) {
    public class Builder {
        public var groups: MutableMap<String, String>? = null
        public var personProperties: MutableMap<String, Any?>? = null
        public var groupProperties: MutableMap<String, Map<String, Any?>>? = null
        public var flagKeys: MutableList<String>? = null
        public var onlyEvaluateLocally: Boolean = false
        public var disableGeoip: Boolean = false

        public fun group(
            type: String,
            key: String,
        ): Builder {
            this.groups = (groups ?: mutableMapOf()).apply { put(type, key) }
            return this
        }

        public fun groups(groups: Map<String, String>): Builder {
            this.groups = (this.groups ?: mutableMapOf()).apply { putAll(groups) }
            return this
        }

        public fun personProperty(
            key: String,
            value: Any?,
        ): Builder {
            this.personProperties = (personProperties ?: mutableMapOf()).apply { put(key, value) }
            return this
        }

        public fun personProperties(personProperties: Map<String, Any?>): Builder {
            this.personProperties = (this.personProperties ?: mutableMapOf()).apply { putAll(personProperties) }
            return this
        }

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

        public fun groupProperties(groupProperties: Map<String, Map<String, Any?>>): Builder {
            this.groupProperties = (this.groupProperties ?: mutableMapOf()).apply { putAll(groupProperties) }
            return this
        }

        /**
         * Restrict the underlying `/flags` request to the given keys. This scopes what the server
         * computes; the snapshot's `only(...)` helper, by contrast, filters in memory.
         */
        public fun flagKeys(flagKeys: List<String>): Builder {
            this.flagKeys = (this.flagKeys ?: mutableListOf()).apply { addAll(flagKeys) }
            return this
        }

        public fun onlyEvaluateLocally(onlyEvaluateLocally: Boolean): Builder {
            this.onlyEvaluateLocally = onlyEvaluateLocally
            return this
        }

        public fun disableGeoip(disableGeoip: Boolean): Builder {
            this.disableGeoip = disableGeoip
            return this
        }

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
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}
