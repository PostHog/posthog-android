package com.posthog

/**
 * Hook invoked before an analytics event is queued.
 *
 * Return the same or a modified [PostHogEvent] to continue, or `null` to drop the event.
 */
public fun interface PostHogBeforeSend {
    /**
     * Runs the hook for [event].
     *
     * @param event Event about to be queued.
     * @return The event to queue, or `null` to drop it.
     */
    public fun run(event: PostHogEvent): PostHogEvent?
}
