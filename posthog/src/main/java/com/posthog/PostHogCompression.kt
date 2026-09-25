package com.posthog

/**
 * Controls whether request bodies are compressed before being sent to the PostHog API.
 */
public enum class PostHogCompression {
    /** Gzip request bodies. */
    GZIP,

    /** Send request bodies uncompressed. */
    NONE,
}
