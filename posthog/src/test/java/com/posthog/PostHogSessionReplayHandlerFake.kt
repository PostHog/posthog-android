package com.posthog

import com.posthog.internal.replay.PostHogSessionReplayHandler

public class PostHogSessionReplayHandlerFake(private var isActive: Boolean) : PostHogIntegration, PostHogSessionReplayHandler {
    public var stopCalled: Boolean = false
    public var startCalled: Boolean = false
    public var resumeCurrent: Boolean? = null
    public var onEventCalled: Boolean = false
    public var lastEventName: String? = null
    public var lastEventProperties: Map<String, Any>? = null
    public var onSessionIdChangedCalled: Boolean = false
    public var stopRequestedByHostCalled: Boolean = false
    public var startRequestedByHostCalled: Boolean = false
    public var startAutomaticallyCalled: Boolean = false
    private var stoppedByHost: Boolean = false

    public fun reset() {
        stopCalled = false
        startCalled = false
        resumeCurrent = null
        onEventCalled = false
        lastEventName = null
        lastEventProperties = null
        onSessionIdChangedCalled = false
        stopRequestedByHostCalled = false
        startRequestedByHostCalled = false
        startAutomaticallyCalled = false
        stoppedByHost = false
    }

    override fun start(resumeCurrent: Boolean) {
        startCalled = true
        this.resumeCurrent = resumeCurrent
        stoppedByHost = false
        isActive = true
    }

    override fun stop() {
        stopCalled = true
        isActive = false
    }

    override fun stopRequestedByHost() {
        stopRequestedByHostCalled = true
        stoppedByHost = true
        stop()
    }

    override fun isStoppedByHost(): Boolean = stoppedByHost

    override fun startRequestedByHost() {
        startRequestedByHostCalled = true
        stoppedByHost = false
    }

    override fun startAutomatically(resumeCurrent: Boolean) {
        startAutomaticallyCalled = true
        if (stoppedByHost) {
            return
        }
        start(resumeCurrent)
    }

    override fun isActive(): Boolean {
        return isActive
    }

    override fun onEvent(
        event: String,
        properties: Map<String, Any>?,
    ) {
        onEventCalled = true
        lastEventName = event
        lastEventProperties = properties
    }

    override fun onSessionIdChanged() {
        onSessionIdChangedCalled = true
    }
}
