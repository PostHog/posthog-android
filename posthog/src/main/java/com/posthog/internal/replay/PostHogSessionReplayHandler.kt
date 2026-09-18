package com.posthog.internal.replay

import com.posthog.PostHogInternal

@PostHogInternal
public interface PostHogSessionReplayHandler {
    public fun start(resumeCurrent: Boolean)

    public fun stop()

    /**
     * Called when the app asks for replay off, e.g. after it reads its own feature flag.
     * The handler keeps that off state, so an automatic start cannot undo the app's decision.
     * Only an explicit start clears it again.
     */
    public fun stopRequestedByHost() {
        stop()
    }

    /**
     * Whether the app asked for replay off and has not asked for it back yet.
     * Automatic start paths must not start recording while this is true.
     */
    public fun isStoppedByHost(): Boolean = false

    /**
     * Called when the app asks for replay back, e.g. by calling startSessionReplay. Revokes the
     * off state regardless of whether recording can actually start right now.
     */
    public fun startRequestedByHost() {}

    /**
     * An automatic resume, e.g. from a feature flag reload. Must never revoke an off state the
     * app set with [stopRequestedByHost].
     */
    public fun startAutomatically(resumeCurrent: Boolean) {
        if (!isStoppedByHost()) {
            start(resumeCurrent)
        }
    }

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
