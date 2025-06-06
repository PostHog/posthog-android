package com.posthog.internal.replay

import com.posthog.PostHogInternal

@PostHogInternal
public interface PostHogSessionReplayHandler {
    public fun start(resumeCurrent: Boolean)

    public fun stop()

    public fun isActive(): Boolean
}
