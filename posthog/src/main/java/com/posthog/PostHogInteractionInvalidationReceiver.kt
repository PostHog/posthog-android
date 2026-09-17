package com.posthog

/** SDK-only cancellation signal. Implementations must only invalidate state and schedule cleanup. */
@PostHogInternal
public interface PostHogInteractionInvalidationReceiver {
    public fun onInteractionInvalidated()
}
