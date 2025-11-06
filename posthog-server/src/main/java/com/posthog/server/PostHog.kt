package com.posthog.server

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
    ) {
        super.captureStateless(
            event,
            distinctId,
            properties,
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
