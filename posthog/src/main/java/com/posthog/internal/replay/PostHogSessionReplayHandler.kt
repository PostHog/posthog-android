package com.posthog.internal.replay

import com.posthog.PostHogInternal

@PostHogInternal
public interface PostHogSessionReplayHandler {
    public fun start(resumeCurrent: Boolean)

    public fun stop()

    public fun isActive(): Boolean

    /**
     * Called when an event is captured.
     * Used for event trigger matching to start session recording.
     */
    public fun onEvent(
        event: String,
        properties: Map<String, Any>? = null,
    )

    /**
     * Called when the session ID changes.
     * Used to stop recording if event triggers are configured and the new session hasn't been activated.
     */
    public fun onSessionIdChanged()
}
