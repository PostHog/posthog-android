package com.posthog

public interface PostHogCoreInterface {
    /**
     * Setup the SDK
     * @param config the SDK configuration
     */
    public fun <T : PostHogConfig> setup(config: T)

    /**
     * Closes the SDK
     */
    public fun close()

    /**
     * Flushes all the events in the Queue right away
     */
    public fun flush()

    /**
     * Enables the SDK to capture events
     */
    public fun optIn()

    /**
     * Disables the SDK to capture events until you [optIn] again
     */
    public fun optOut()

    /**
     * Checks if the [optOut] mode is enabled or disabled
     */
    public fun isOptOut(): Boolean

    /**
     * Enables or disables the debug mode
     * @param enable true to enable SDK debug logging, false to disable it.
     */
    public fun debug(enable: Boolean = true)
}
