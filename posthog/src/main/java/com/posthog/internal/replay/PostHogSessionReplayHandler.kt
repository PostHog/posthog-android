package com.posthog.internal.replay

import com.posthog.PostHogInternal

@PostHogInternal
public interface PostHogSessionReplayHandler {
    public fun start(resumeCurrent: Boolean)

    /**
     * Called when the app explicitly asks to start recording, before any gate can refuse it.
     */
    public fun onStartRequested() {}

    /**
     * Called when the app explicitly asks to stop recording.
     */
    public fun onStopRequested() {}

    public fun stop()

    public fun isActive(): Boolean

    /**
     * Snapshot of `$recording_status` and `$sdk_debug_replay_*` properties. PostHog attaches only the
     * required subset to every event and the full map to at most one SDK event per 30s. Must always
     * include `$recording_status`; the rest are omitted when not applicable.
     */
    public fun debugProperties(): Map<String, Any>

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

    /**
     * Called when feature flags are loaded, before any automatic start.
     * Flags can activate or deactivate session recording trigger groups.
     */
    public fun onFeatureFlagsLoaded() {}
}
