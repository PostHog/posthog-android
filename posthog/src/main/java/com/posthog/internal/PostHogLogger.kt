package com.posthog.internal

import com.posthog.PostHogInternal

/**
 * Interface for logging debug messages into the System out or Logcat depending on the implementation
 */
@PostHogInternal
public interface PostHogLogger {
    public fun log(message: String)

    public fun isEnabled(): Boolean
}
