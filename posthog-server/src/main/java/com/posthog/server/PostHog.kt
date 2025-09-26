package com.posthog.server

import com.posthog.PostHogStateless
import com.posthog.PostHogStatelessInterface

public class PostHog : PostHogInterface {
    private var instance: PostHogStatelessInterface? = null

    override fun <T : PostHogConfig> setup(config: T) {
        instance = PostHogStateless.with(config.asCoreConfig())
    }

    override fun close() {
        instance?.close()
    }

    override fun identify(
        distinctId: String,
        userProperties: Map<String, Any>?,
        userPropertiesSetOnce: Map<String, Any>?,
    ) {
        instance?.identify(
            distinctId,
            userProperties,
            userPropertiesSetOnce,
        )
    }

    override fun flush() {
        instance?.flush()
    }

    override fun debug(enable: Boolean) {
        instance?.debug(enable)
    }

    override fun capture(
        distinctId: String,
        event: String,
        properties: Map<String, Any>?,
        userProperties: Map<String, Any>?,
        userPropertiesSetOnce: Map<String, Any>?,
        groups: Map<String, String>?,
    ) {
        instance?.captureStateless(
            event,
            distinctId,
            properties,
            userProperties,
            userPropertiesSetOnce,
            groups,
        )
    }

    override fun isFeatureEnabled(
        distinctId: String,
        key: String,
        defaultValue: Boolean,
    ): Boolean {
        return instance?.isFeatureEnabledStateless(distinctId, key, defaultValue) ?: false
    }

    override fun getFeatureFlag(
        distinctId: String,
        key: String,
        defaultValue: Any?,
    ): Any? {
        return instance?.getFeatureFlagStateless(distinctId, key, defaultValue)
    }

    override fun getFeatureFlagPayload(
        distinctId: String,
        key: String,
        defaultValue: Any?,
    ): Any? {
        return instance?.getFeatureFlagPayloadStateless(distinctId, key, defaultValue)
    }

    override fun group(
        distinctId: String,
        type: String,
        key: String,
        groupProperties: Map<String, Any>?,
    ) {
        instance?.groupStateless(
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
        instance?.aliasStateless(
            distinctId,
            alias,
        )
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
