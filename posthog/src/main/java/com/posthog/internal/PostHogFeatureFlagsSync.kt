package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogOnFeatureFlags

internal class PostHogFeatureFlagsSync(
    private val config: PostHogConfig,
    private val api: PostHogApi,
) : PostHogFeatureFlagsInterface {
    override fun loadFeatureFlags(
        distinctId: String,
        anonymousId: String?,
        groups: Map<String, Any>?,
        onFeatureFlags: PostHogOnFeatureFlags?,
    ) {
        // NoOp since its all sync - not cached
    }

    private fun loadFeatureFlagsFromNetwork(
        distinctId: String,
        anonymousId: String?,
        groups: Map<String, Any>?,
    ): Pair<Map<String, Any>?, Map<String, Any?>?> {
        try {
            val response = api.decide(distinctId, anonymousId = anonymousId, groups = groups)

            response?.let {
                val featureFlags = response.featureFlags
                val normalizedPayloads = normalizePayloads(config.serializer, response.featureFlagPayloads)

                return Pair(featureFlags, normalizedPayloads)
            }
        } catch (e: Throwable) {
            config.logger.log("Loading feature flags from network failed: $e")
        }
        return Pair(null, null)
    }

    override fun isFeatureEnabled(
        key: String,
        defaultValue: Boolean,
        distinctId: String,
        anonymousId: String?,
        groups: Map<String, Any>?,
    ): Boolean {
        val (flags, _) = loadFeatureFlagsFromNetwork(distinctId, anonymousId = anonymousId, groups = groups)

        val value = flags?.get(key)

        return normalizeBoolean(value, defaultValue)
    }

    override fun getFeatureFlagPayload(
        key: String,
        defaultValue: Any?,
        distinctId: String,
        anonymousId: String?,
        groups: Map<String, Any>?,
    ): Any? {
        val (_, payloads) = loadFeatureFlagsFromNetwork(distinctId, anonymousId = anonymousId, groups = groups)

        return payloads?.get(key) ?: defaultValue
    }

    override fun getFeatureFlag(
        key: String,
        defaultValue: Any?,
        distinctId: String,
        anonymousId: String?,
        groups: Map<String, Any>?,
    ): Any? {
        val (flags, _) = loadFeatureFlagsFromNetwork(distinctId, anonymousId = anonymousId, groups = groups)

        return flags?.get(key) ?: defaultValue
    }

    override fun getAllFeatureFlags(
        distinctId: String,
        anonymousId: String?,
        groups: Map<String, Any>?,
    ): Map<String, Any>? {
        val (flags, _) = loadFeatureFlagsFromNetwork(distinctId, anonymousId = anonymousId, groups = groups)

        return flags?.ifEmpty { null }
    }

    override fun getAllFeatureFlagsAndPayloads(
        distinctId: String,
        anonymousId: String?,
        groups: Map<String, Any>?,
    ): Pair<Map<String, Any>?, Map<String, Any?>?> {
        val (flags, payloads) = loadFeatureFlagsFromNetwork(distinctId, anonymousId = anonymousId, groups = groups)

        return Pair(flags, payloads)
    }

    override fun clear() {
        // NoOp since its all sync - not cached
    }
}
