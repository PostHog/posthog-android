package com.posthog.internal

import com.posthog.PostHogInternal

/**
 * Interface for checking the network connectivity before trying to send events over the wire
 */
@PostHogInternal
public fun interface PostHogNetworkStatus {
    public fun isConnected(): Boolean
}
