package com.posthog

/**
 * Controls whether request bodies are compressed before being sent to the PostHog API.
 */
public enum class PostHogCompression {
    /** Gzip request bodies. See [PostHogConfig.compression] for the automatic fallback. */
    GZIP,

    /** Send request bodies uncompressed. */
    NONE,
}
