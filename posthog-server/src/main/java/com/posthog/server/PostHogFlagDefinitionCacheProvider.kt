package com.posthog.server

/**
 * Shared cache provider for local-evaluation feature flag definitions.
 *
 * Implementations usually coordinate leadership across SDK instances. When
 * [shouldFetchFlagDefinitions] returns true this instance fetches definitions from
 * PostHog and receives them through [onFlagDefinitionsReceived]. When it returns
 * false this instance reads definitions from [getFlagDefinitions] instead.
 *
 * Provider errors are handled defensively by the SDK: failed reads fall back to the
 * API only when no definitions are already loaded, and failed writes/shutdowns are
 * logged without failing flag evaluation.
 */
public interface PostHogFlagDefinitionCacheProvider {
    /**
     * Return cached flag definitions as a JSON string, or null when the cache is empty or unavailable.
     *
     * The JSON string should use the shared local-evaluation definitions shape returned by PostHog's
     * `/flags/definitions` endpoint: `flags`, `group_type_mapping`, and `cohorts`.
     */
    public fun getFlagDefinitions(): String?

    /**
     * Return true when this SDK instance should fetch definitions from PostHog.
     */
    public fun shouldFetchFlagDefinitions(): Boolean

    /**
     * Called with cached flag definitions JSON after this SDK instance successfully fetches fresh definitions from PostHog.
     *
     * Implementations should store this string as an opaque blob to keep cache contents shareable
     * across PostHog server SDKs.
     */
    public fun onFlagDefinitionsReceived(data: String): Unit

    /**
     * Clean up any resources held by the provider, such as distributed locks.
     */
    public fun shutdown(): Unit
}
