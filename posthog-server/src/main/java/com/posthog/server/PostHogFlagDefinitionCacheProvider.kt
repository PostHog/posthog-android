package com.posthog.server

import com.google.gson.annotations.SerializedName
import com.posthog.internal.FlagDefinition
import com.posthog.internal.PropertyGroup

/**
 * Feature flag definitions that can be shared between PostHog server SDK instances.
 *
 * Cache providers can store this data in a shared cache so only one process needs to
 * fetch local-evaluation flag definitions from PostHog while other processes read the
 * latest definitions from the cache.
 */
public data class PostHogFlagDefinitionCacheData(
    /**
     * Feature flag definitions returned by the local-evaluation definitions endpoint.
     */
    public val flags: List<FlagDefinition>,
    /**
     * Mapping of group type indexes to group names used by group-based flags.
     */
    @SerializedName("group_type_mapping")
    public val groupTypeMapping: Map<String, String>,
    /**
     * Cohort definitions used while evaluating flags locally.
     */
    public val cohorts: Map<String, PropertyGroup>,
)

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
     * Return cached flag definitions, or null when the cache is empty or unavailable.
     */
    public fun getFlagDefinitions(): PostHogFlagDefinitionCacheData?

    /**
     * Return true when this SDK instance should fetch definitions from PostHog.
     */
    public fun shouldFetchFlagDefinitions(): Boolean

    /**
     * Called after this SDK instance successfully fetches fresh definitions from PostHog.
     */
    public fun onFlagDefinitionsReceived(data: PostHogFlagDefinitionCacheData): Unit

    /**
     * Clean up any resources held by the provider, such as distributed locks.
     */
    public fun shutdown(): Unit
}
