package com.posthog.internal

import com.posthog.PostHogInternal

/**
 * Interface for logging debug messages into the System out or Logcat depending on the implementation
 */
@PostHogInternal
public interface PostHogLogger {
    public fun log(message: String)

    /**
     * Reports a problem that keeps part of the SDK from working, such as a missing dependency.
     * Implementations print this even when debug is off, because the host app has no other way
     * to see the problem.
     */
    public fun logWarning(message: String) {
        log(message)
    }

    public fun isEnabled(): Boolean
}
