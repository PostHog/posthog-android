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
    public var debugProperties: Map<String, Any> = mapOf("\$recording_status" to "disabled")
    public var throwOnDebugProperties: Boolean = false

    // false models a start held back, e.g. waiting for an event trigger
    public var activateOnStart: Boolean = true

    public fun reset() {
        stopCalled = false
        startCalled = false
        resumeCurrent = null
        onEventCalled = false
        lastEventName = null
        lastEventProperties = null
        onSessionIdChangedCalled = false
    }

    override fun start(resumeCurrent: Boolean) {
        startCalled = true
        this.resumeCurrent = resumeCurrent
        isActive = activateOnStart
    }

    // runs at the start of each stop() call, before the handler goes inactive
    public var onStop: (() -> Unit)? = null

    override fun stop() {
        onStop?.invoke()
        stopCalled = true
        isActive = false
    }

    // runs on each isActive() call, so a test can interleave work at that point
    public var onIsActive: (() -> Unit)? = null

    override fun isActive(): Boolean {
        onIsActive?.invoke()
        return isActive
    }

    override fun debugProperties(): Map<String, Any> {
        if (throwOnDebugProperties) {
            throw RuntimeException("debugProperties failed")
        }
        return debugProperties
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
