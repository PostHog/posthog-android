package com.posthog.server.internal

import com.posthog.PostHogConfig
import com.posthog.internal.FeatureFlag
import com.posthog.internal.PostHogApi
import com.posthog.internal.PostHogFeatureFlagsInterface

internal class PostHogFeatureFlags(
    private val config: PostHogConfig,
    private val api: PostHogApi,
    private val cacheMaxAgeMs: Int,
    private val cacheMaxSize: Int,
) : PostHogFeatureFlagsInterface {
    private val cache =
        PostHogFeatureFlagCache(
            maxSize = cacheMaxSize,
            maxAgeMs = cacheMaxAgeMs,
        )

    override fun getFeatureFlag(
        key: String,
        defaultValue: Any?,
        distinctId: String?,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): Any? {
        val flag =
            getFeatureFlags(
                distinctId,
                groups,
                personProperties,
                groupProperties,
            )?.get(key)
        return flag?.variant ?: flag?.enabled ?: defaultValue
    }

    override fun getFeatureFlagPayload(
        key: String,
        defaultValue: Any?,
        distinctId: String?,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): Any? {
        return getFeatureFlags(
            distinctId,
            groups,
            personProperties,
            groupProperties,
        )?.get(key)?.metadata?.payload
            ?: defaultValue
    }

    override fun getFeatureFlags(
        distinctId: String?,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): Map<String, FeatureFlag>? {
        if (distinctId == null) {
            config.logger.log("getFeatureFlags called but no distinctId available for API call")
            return null
        }

        // Create cache key from parameters
        val cacheKey =
            FeatureFlagCacheKey(
                distinctId = distinctId,
                groups = groups,
                personProperties = personProperties,
                groupProperties = groupProperties,
            )

        // Check cache first
        val cachedFlags = cache.get(cacheKey)
        if (cachedFlags != null) {
            config.logger.log("Feature flags cache hit for distinctId: $distinctId")
            return cachedFlags
        }

        // Cache miss
        config.logger.log("Feature flags cache miss for distinctId: $distinctId")
        return try {
            val response = api.flags(distinctId, null, groups, personProperties, groupProperties)
            val flags = response?.flags
            cache.put(cacheKey, flags)
            flags
        } catch (e: Throwable) {
            config.logger.log("Loading feature flags failed: $e")
            null
        }
    }

    override fun clear() {
        cache.clear()
        config.logger.log("Feature flags cache cleared")
    }
}
