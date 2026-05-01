package com.posthog.server

import com.google.gson.Gson
import com.posthog.internal.FeatureFlag
import com.posthog.server.internal.EvaluationsHost
import com.posthog.server.internal.FeatureFlagError
import java.util.Collections

/**
 * A snapshot of feature flag evaluations for one [distinctId], produced by
 * [PostHogInterface.evaluateFlags]. The snapshot lets callers introspect every flag once without
 * issuing additional `/flags` requests, and tracks which flags were accessed so that follow-up
 * `capture(flags = …)` calls can attach only the relevant subset.
 *
 * Accessor side-effects:
 *   - [isEnabled] / [getFlag] fire `$feature_flag_called` (deduped by the same per-distinct-id LRU
 *     used by [PostHogInterface.getFeatureFlag]). Empty/blank distinctId short-circuits the event.
 *     Reads for unknown keys still fire a `$feature_flag_called` event with
 *     `$feature_flag_error: flag_missing` so dashboards see the lookup attempt.
 *   - [getFlagPayload] does not fire any event.
 *
 * Filtered clones from [onlyAccessed] / [only] are independent of the parent — accessing flags on
 * the clone does not back-propagate into the parent's "accessed" set.
 */
public class PostHogFeatureFlagEvaluations internal constructor(
    public val distinctId: String?,
    flagMap: Map<String, FeatureFlag>,
    locallyEvaluated: Map<String, Boolean>,
    public val requestId: String?,
    public val evaluatedAt: Long?,
    public val definitionsLoadedAt: Long?,
    private val responseError: String?,
    private val host: EvaluationsHost,
    initialAccessed: Set<String> = emptySet(),
) {
    private val flagMap: Map<String, FeatureFlag> = Collections.unmodifiableMap(LinkedHashMap(flagMap))
    private val locallyEvaluated: Map<String, Boolean> = Collections.unmodifiableMap(HashMap(locallyEvaluated))

    private val accessLock = Any()
    private val accessed: MutableSet<String> = HashSet(initialAccessed)

    /** Returns the snapshotted flag keys in iteration order of the underlying map. */
    public val keys: List<String>
        get() = flagMap.keys.toList()

    /**
     * Internal access to the rich [FeatureFlag] map. Not exposed publicly because [FeatureFlag]
     * lives in `com.posthog.internal` and shouldn't leak through the public API. Same-package
     * code (the server `PostHog` class) reads this when building `$feature/<key>` properties for
     * `capture(flags = …)`.
     */
    internal val flags: Map<String, FeatureFlag>
        get() = flagMap

    /**
     * Returns whether the flag is enabled. Unknown flags return false. Records access; fires a
     * deduped `$feature_flag_called` event (with `$feature_flag_error: flag_missing` for unknown
     * keys), except when this snapshot has no resolvable distinctId.
     */
    public fun isEnabled(key: String): Boolean {
        val flag = flagMap[key]
        recordAccess(key, flag)
        if (flag == null) return false
        return flag.variant?.isNotEmpty() ?: flag.enabled
    }

    /**
     * Returns the flag value: the variant string, the boolean enabled flag, or null when the flag
     * is unknown. Records access; fires a deduped `$feature_flag_called` event (with
     * `$feature_flag_error: flag_missing` for unknown keys), except when this snapshot has no
     * resolvable distinctId.
     */
    public fun getFlag(key: String): Any? {
        val flag = flagMap[key]
        recordAccess(key, flag)
        if (flag == null) return null
        return flag.variant ?: flag.enabled
    }

    /**
     * Returns the raw payload string for the flag, or null when the flag is unknown or has no
     * payload. The server returns payloads as JSON-encoded strings; use [getFlagPayloadAs] when
     * you want the deserialized value. Does not fire any event and does not record the access.
     */
    public fun getFlagPayload(key: String): String? {
        return flagMap[key]?.metadata?.payload
    }

    /**
     * Returns the flag payload deserialized from JSON to type [T], or null when the flag is
     * unknown, has no payload, or deserialization fails.
     */
    public inline fun <reified T> getFlagPayloadAs(key: String): T? = getFlagPayloadAs(key, T::class.java)

    /**
     * Returns the flag payload deserialized from JSON to [clazz], or null when the flag is
     * unknown, has no payload, or deserialization fails. The server returns payloads as
     * JSON-encoded strings, so this parses the raw string with Gson.
     */
    public fun <T> getFlagPayloadAs(
        key: String,
        clazz: Class<T>,
    ): T? {
        val raw = flagMap[key]?.metadata?.payload ?: return null
        // Always Gson-parse: a raw JSON string like `"\"hello\""` should deserialize to `hello`,
        // not pass through as the quoted form. Same intent for primitives, lists, maps, etc.
        return try {
            PAYLOAD_GSON.fromJson(raw, clazz)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Returns a filtered snapshot containing only the flags accessed on this instance via
     * [isEnabled] or [getFlag]. Returns an empty snapshot when no flag has been accessed yet.
     */
    public fun onlyAccessed(): PostHogFeatureFlagEvaluations {
        val accessedKeys =
            synchronized(accessLock) { accessed.toSet() }
        return cloneWith(accessedKeys)
    }

    /**
     * Returns a filtered snapshot containing only the named flags. Unknown keys are dropped and
     * each one logs a warning so callers notice typos.
     */
    public fun only(keys: Collection<String>): PostHogFeatureFlagEvaluations {
        val resolved = LinkedHashSet<String>()
        for (key in keys) {
            if (flagMap.containsKey(key)) {
                resolved.add(key)
            } else {
                host.logWarning(
                    "PostHogFeatureFlagEvaluations.only(...) called with unknown flag key '$key'; " +
                        "dropping it from the filtered snapshot.",
                )
            }
        }
        return cloneWith(resolved)
    }

    /** Java-friendly varargs alias of [only]. */
    public fun only(vararg keys: String): PostHogFeatureFlagEvaluations = only(keys.toList())

    private fun cloneWith(keep: Collection<String>): PostHogFeatureFlagEvaluations {
        val filtered = LinkedHashMap<String, FeatureFlag>()
        for (key in keep) {
            flagMap[key]?.let { filtered[key] = it }
        }
        return PostHogFeatureFlagEvaluations(
            distinctId = distinctId,
            flagMap = filtered,
            locallyEvaluated = locallyEvaluated.filterKeys { filtered.containsKey(it) },
            requestId = requestId,
            evaluatedAt = evaluatedAt,
            definitionsLoadedAt = definitionsLoadedAt,
            responseError = responseError,
            host = host,
            initialAccessed = emptySet(),
        )
    }

    private fun recordAccess(
        key: String,
        flag: FeatureFlag?,
    ) {
        synchronized(accessLock) { accessed.add(key) }
        if (distinctId.isNullOrBlank()) return

        val value: Any = flag?.let { it.variant ?: it.enabled } ?: false
        val props = mutableMapOf<String, Any>()
        if (flag != null) {
            props["\$feature_flag_id"] = flag.metadata.id
            props["\$feature_flag_version"] = flag.metadata.version
            flag.reason?.description?.let { props["\$feature_flag_reason"] = it }
            if (locallyEvaluated[key] == true) {
                props["locally_evaluated"] = true
            }
        }
        requestId?.let { props["\$feature_flag_request_id"] = it }
        evaluatedAt?.let { props["\$feature_flag_evaluated_at"] = it }
        definitionsLoadedAt?.let { props["\$feature_flag_definitions_loaded_at"] = it }

        val error =
            buildList<String> {
                responseError?.let { add(it) }
                if (flag == null) add(FeatureFlagError.FLAG_MISSING)
            }
        if (error.isNotEmpty()) {
            props["\$feature_flag_error"] = error.joinToString(",")
        }

        host.captureFeatureFlagCalled(distinctId, key, value, props)
    }

    public companion object {
        private val PAYLOAD_GSON: Gson = Gson()

        /**
         * Builds an empty snapshot. Used internally for the empty-distinctId short-circuit; any
         * accessor invocation on an empty snapshot is a no-op.
         */
        @JvmSynthetic
        internal fun empty(host: EvaluationsHost): PostHogFeatureFlagEvaluations {
            return PostHogFeatureFlagEvaluations(
                distinctId = null,
                flagMap = emptyMap(),
                locallyEvaluated = emptyMap(),
                requestId = null,
                evaluatedAt = null,
                definitionsLoadedAt = null,
                responseError = null,
                host = host,
            )
        }
    }
}
