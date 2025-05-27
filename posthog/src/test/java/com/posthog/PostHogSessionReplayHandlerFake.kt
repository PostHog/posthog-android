package com.posthog

import com.posthog.internal.replay.PostHogSessionReplayHandler

public class PostHogSessionReplayHandlerFake(private var isActive: Boolean) : PostHogIntegration, PostHogSessionReplayHandler {
    public var stopCalled: Boolean = false
    public var startCalled: Boolean = false
    public var resumeCurrent: Boolean? = null

    public fun reset() {
        stopCalled = false
        startCalled = false
        resumeCurrent = null
    }

    override fun start(resumeCurrent: Boolean) {
        startCalled = true
        this.resumeCurrent = resumeCurrent
        isActive = true
    }

    override fun stop() {
        stopCalled = true
        isActive = false
    }

    override fun isActive(): Boolean {
        return isActive
    }
}
