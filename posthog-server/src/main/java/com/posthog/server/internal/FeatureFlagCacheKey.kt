package com.posthog.server.internal

/**
 * Cache key for feature flag requests based on the parameters used in the API call
 */
internal data class FeatureFlagCacheKey(
    val distinctId: String?,
    val groups: Map<String, String>?,
    val personProperties: Map<String, Any?>?,
    val groupProperties: Map<String, Map<String, Any?>>?,
)
