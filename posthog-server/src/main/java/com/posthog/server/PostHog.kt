package com.posthog.server

import com.posthog.FeatureFlagResult
import com.posthog.PostHog
import com.posthog.PostHogStateless
import com.posthog.server.internal.PostHogFeatureFlags

public class PostHog : PostHogStateless(), PostHogInterface {
    override fun <T : PostHogConfig> setup(config: T) {
        super.setup(config.asCoreConfig())
    }

    override fun close() {
        super.close()
    }

    override fun identify(
        distinctId: String,
        userProperties: Map<String, Any>?,
        userPropertiesSetOnce: Map<String, Any>?,
    ) {
        super<PostHogStateless>.identify(
            distinctId,
            userProperties,
            userPropertiesSetOnce,
        )
    }

    override fun flush() {
        super.flush()
    }

    override fun debug(enable: Boolean) {
        super.debug(enable)
    }

    override fun capture(
        distinctId: String,
        event: String,
        properties: Map<String, Any>?,
        userProperties: Map<String, Any>?,
        userPropertiesSetOnce: Map<String, Any>?,
        groups: Map<String, String>?,
        timestamp: java.util.Date?,
        appendFeatureFlags: Boolean,
    ) {
        val mergedProperties =
            if (appendFeatureFlags) {
                mergeFeatureFlagProperties(
                    distinctId = distinctId,
                    groups = groups,
                    userProperties = userProperties,
                    groupProperties = null,
                    properties = properties,
                )
            } else {
                properties
            }

        super.captureStateless(
            event,
            distinctId,
            mergedProperties,
            userProperties,
            userPropertiesSetOnce,
            groups,
            timestamp,
        )
    }

    override fun isFeatureEnabled(
        distinctId: String,
        key: String,
        defaultValue: Boolean,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): Boolean {
        return super.isFeatureEnabledStateless(
            distinctId,
            key,
            defaultValue,
            groups,
            personProperties,
            groupProperties,
        )
    }

    override fun getFeatureFlag(
        distinctId: String,
        key: String,
        defaultValue: Any?,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): Any? {
        return super.getFeatureFlagStateless(
            distinctId,
            key,
            defaultValue,
            groups,
            personProperties,
            groupProperties,
        )
    }

    override fun getFeatureFlagPayload(
        distinctId: String,
        key: String,
        defaultValue: Any?,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): Any? {
        return super.getFeatureFlagPayloadStateless(
            distinctId,
            key,
            defaultValue,
            groups,
            personProperties,
            groupProperties,
        )
    }

    override fun getFeatureFlagResult(
        distinctId: String,
        key: String,
        groups: Map<String, String>?,
        personProperties: Map<String, Any?>?,
        groupProperties: Map<String, Map<String, Any?>>?,
    ): FeatureFlagResult? {
        return super.getFeatureFlagResultStateless(
            distinctId,
            key,
            groups,
            personProperties,
            groupProperties,
        )
    }

    override fun group(
        distinctId: String,
        type: String,
        key: String,
        groupProperties: Map<String, Any>?,
    ) {
        super.groupStateless(
            distinctId,
            type,
            key,
            groupProperties,
        )
    }

    override fun alias(
        distinctId: String,
        alias: String,
    ) {
        super.aliasStateless(
            distinctId,
            alias,
        )
    }

    override fun reloadFeatureFlags() {
        (featureFlags as? PostHogFeatureFlags)?.loadFeatureFlagDefinitions()
    }

    override fun captureException(
        exception: Throwable,
        distinctId: String?,
        properties: Map<String, Any>?,
    ) {
        super.captureExceptionStateless(
            exception,
            distinctId = distinctId,
            properties = properties,
        )
    }

    private fun mergeFeatureFlagProperties(
        distinctId: String,
        groups: Map<String, String>?,
        userProperties: Map<String, Any>?,
        groupProperties: Map<String, Map<String, Any>>?,
        properties: Map<String, Any>?,
    ): Map<String, Any> {
        val props = properties?.toMutableMap() ?: mutableMapOf()
        val flags =
            (featureFlags as? PostHogFeatureFlags)?.getFeatureFlags(
                distinctId = distinctId,
                groups = groups,
                groupProperties = groupProperties,
                personProperties = userProperties,
            )

        if (flags != null && flags.isNotEmpty()) {
            val activeFlags = mutableListOf<String>()

            for ((key, flag) in flags) {
                val flagValue: Any = flag.variant ?: flag.enabled
                props["\$feature/$key"] = flagValue
                val isActive =
                    when (flagValue) {
                        is Boolean -> flagValue
                        is String -> flagValue.isNotEmpty()
                        else -> true
                    }
                if (isActive) {
                    activeFlags.add(key)
                }
            }

            props["\$active_feature_flags"] = activeFlags
        }

        return props
    }

    public companion object {
        /**
         * Set up the SDK and returns an instance that you can hold and pass it around
         * @param T the type of the Config
         * @property config the Config
         */
        @JvmStatic
        public fun <T : PostHogConfig> with(config: T): PostHogInterface {
            val instance = PostHog()
            instance.setup(config)
            return instance
        }
    }
}
