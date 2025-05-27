package com.posthog

import com.posthog.internal.replay.PostHogSessionReplayHandler

public class PostHogSessionReplayHandlerFake(private val isActive: Boolean) : PostHogIntegration, PostHogSessionReplayHandler {
    override fun start(resumeCurrent: Boolean) {
    }

    override fun stop() {
    }

    override fun isActive(): Boolean {
        return isActive
    }
}
