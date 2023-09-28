package com.posthog

public interface PostHogInterface {
    public fun <T : PostHogConfig> setup(config: T)

    public fun close()

    public fun capture(
        event: String,
        distinctId: String? = null, // TODO: should distinctId be here or just in identify?
        properties: Map<String, Any>? = null,
        userProperties: Map<String, Any>? = null,
        userPropertiesSetOnce: Map<String, Any>? = null,
        groupProperties: Map<String, Any>? = null,
    )

    public fun identify(
        distinctId: String,
        properties: Map<String, Any>? = null,
        userProperties: Map<String, Any>? = null,
        userPropertiesSetOnce: Map<String, Any>? = null,
        // TODO: should we have groupProperties here?
    )

    public fun reloadFeatureFlagsRequest(onFeatureFlags: PostHogOnFeatureFlags? = null)

    public fun isFeatureEnabled(key: String, defaultValue: Boolean = false): Boolean

    public fun getFeatureFlag(key: String, defaultValue: Any? = null): Any?

    public fun getFeatureFlagPayload(key: String, defaultValue: Any? = null): Any?

    public fun flush()

    public fun reset()

    public fun optIn()

    public fun optOut()

    public fun group(type: String, key: String, groupProperties: Map<String, Any>? = null)

    public fun screen(screenTitle: String, properties: Map<String, Any>? = null)

    public fun alias(alias: String, properties: Map<String, Any>? = null)

    public fun isOptOut(): Boolean

    public fun register(key: String, value: Any)

    public fun unregister(key: String)
}
