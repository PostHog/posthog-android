package com.posthog

public interface PostHogCoreInterface {
    /**
     * Setup the SDK
     *
     * posthog-capability: setup
     *
     * @param config the SDK configuration
     */
    public fun <T : PostHogConfig> setup(config: T)

    /**
     * Closes the SDK
     *
     * posthog-capability: shutdown
     */
    public fun close()

    /**
     * Identifies the user
     * Docs https://posthog.com/docs/product-analytics/identify
     *
     * posthog-capability: identify
     *
     * @param distinctId the distinctId
     * @param userProperties the user properties, set as a "$set" property, Docs https://posthog.com/docs/product-analytics/user-properties
     * @param userPropertiesSetOnce the user properties to set only once, set as a "$set_once" property, Docs https://posthog.com/docs/product-analytics/user-properties
     */
    public fun identify(
        distinctId: String,
        userProperties: Map<String, Any>? = null,
        userPropertiesSetOnce: Map<String, Any>? = null,
    )

    /**
     * Flushes all the events in the Queue right away
     *
     * posthog-capability: flush
     */
    public fun flush()

    /**
     * Enables the SDK to capture events
     *
     * posthog-capability: opt-in
     */
    public fun optIn()

    /**
     * Disables the SDK to capture events until you [optIn] again
     */
    public fun optOut()

    /**
     * Checks if the [optOut] mode is enabled or disabled
     *
     * posthog-capability: is-opt-out
     */
    public fun isOptOut(): Boolean

    /**
     * Enables or disables the debug mode
     *
     * posthog-capability: debug
     *
     * @param enable true to enable SDK debug logging, false to disable it.
     */
    public fun debug(enable: Boolean = true)
}
