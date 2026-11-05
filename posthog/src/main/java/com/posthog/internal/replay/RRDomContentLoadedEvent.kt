package com.posthog.internal.replay

internal class RRDomContentLoadedEvent(timestamp: Long) : RREvent(
    type = RREventType.DomContentLoaded,
    timestamp = timestamp,
)
