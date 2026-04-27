package com.posthog.server

import com.posthog.internal.FeatureFlag
import com.posthog.server.internal.EvaluationsHost

/**
 * A snapshot of feature flag evaluations for one [distinctId], produced by
 * [PostHogInterface.evaluateFlags]. The snapshot lets callers introspect every flag once without
 * issuing additional `/flags` requests, and tracks which flags were accessed so that follow-up
 * `capture(flags = …)` calls can attach only the relevant subset.
 *
 * Accessor side-effects:
 *   - [isEnabled] / [getFlag] fire `$feature_flag_called` (deduped by the same per-distinct-id LRU
 *     used by [PostHogInterface.getFeatureFlag]). Empty/blank distinctId short-circuits the event.
 *   - [getFlagPayload] does not fire any event.
 *
 * Filtered clones from [onlyAccessed] / [only] are independent of the parent — accessing flags on
 * the clone does not back-propagate into the parent's "accessed" set.
 */
public class PostHogFeatureFlagEvaluations internal constructor(
    public val distinctId: String?,
    private val flagMap: Map<String, FeatureFlag>,
    private val locallyEvaluated: Map<String, Boolean>,
    public val requestId: String?,
    public val evaluatedAt: Long?,
    public val definitionsLoadedAt: Long?,
    private val host: EvaluationsHost,
    initialAccessed: Set<String> = emptySet(),
) {
    private val accessLock = Any()
    private val accessed: MutableSet<String> = HashSet(initialAccessed)

    /** Returns the snapshotted flag keys in iteration order of the underlying map. */
    public val keys: List<String>
        get() = flagMap.keys.toList()

    /** Returns the immutable flag map this snapshot was built from. */
    public val flags: Map<String, FeatureFlag>
        get() = flagMap

    /**
     * Returns whether the flag is enabled. Unknown flags return false. Records access and fires a
     * deduped `$feature_flag_called` event, except when this snapshot has no resolvable
     * distinctId.
     */
    public fun isEnabled(key: String): Boolean {
        val flag = flagMap[key]
        recordAccess(key, flag)
        if (flag == null) return false
        return flag.variant?.isNotEmpty() ?: flag.enabled
    }

    /**
     * Returns the flag value: the variant string, the boolean enabled flag, or null when the flag
     * is unknown. Records access and fires a deduped `$feature_flag_called` event, except when
     * this snapshot has no resolvable distinctId.
     */
    public fun getFlag(key: String): Any? {
        val flag = flagMap[key]
        recordAccess(key, flag)
        if (flag == null) return null
        return flag.variant ?: flag.enabled
    }

    /**
     * Returns the flag payload. Does not fire any event and does not record the access — payloads
     * are an inert read.
     */
    public fun getFlagPayload(key: String): Any? {
        return flagMap[key]?.metadata?.payload
    }

    /**
     * Returns a filtered snapshot containing only the flags accessed on this instance via
     * [isEnabled] or [getFlag]. When no flag has been accessed yet, logs a warning and returns a
     * clone containing every flag instead of silently dropping data.
     */
    public fun onlyAccessed(): PostHogFeatureFlagEvaluations {
        val accessedKeys =
            synchronized(accessLock) { accessed.toSet() }
        if (accessedKeys.isEmpty()) {
            if (host.warningsEnabled) {
                host.logWarning(
                    "PostHogFeatureFlagEvaluations.onlyAccessed() called before any flag was " +
                        "accessed; returning all $${flagMap.size} flags instead of an empty snapshot.",
                )
            }
            return cloneWith(flagMap.keys)
        }
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
            } else if (host.warningsEnabled) {
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
            host = host,
            initialAccessed = emptySet(),
        )
    }

    private fun recordAccess(
        key: String,
        flag: FeatureFlag?,
    ) {
        synchronized(accessLock) { accessed.add(key) }
        if (flag == null) return
        if (distinctId.isNullOrBlank()) return

        val value: Any = flag.variant ?: flag.enabled
        val props = mutableMapOf<String, Any>()
        props["\$feature_flag_id"] = flag.metadata.id
        props["\$feature_flag_version"] = flag.metadata.version
        flag.reason?.description?.let { props["\$feature_flag_reason"] = it }
        requestId?.let { props["\$feature_flag_request_id"] = it }
        evaluatedAt?.let { props["\$feature_flag_evaluated_at"] = it }
        definitionsLoadedAt?.let { props["\$feature_flag_definitions_loaded_at"] = it }
        if (locallyEvaluated[key] == true) {
            props["locally_evaluated"] = true
        }

        host.captureFeatureFlagCalled(distinctId, key, value, props)
    }

    public companion object {
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
                host = host,
            )
        }
    }
}
