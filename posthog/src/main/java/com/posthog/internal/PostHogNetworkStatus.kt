package com.posthog.internal

import com.posthog.PostHogInternal

/**
 * Interface for checking the network connectivity before trying to send events over the wire
 * and optionally observing network availability changes
 */
@PostHogInternal
public interface PostHogNetworkStatus {
    public fun isConnected(): Boolean

    /**
     * Register a callback to be notified when the network becomes available.
     * The callback should trigger a flush of queued events.
     * Default implementation is a no-op for backward compatibility.
     */
    public fun register(callback: () -> Unit) {}

    /**
     * Unregister the network availability callback.
     * Default implementation is a no-op for backward compatibility.
     */
    public fun unregister() {}
}
