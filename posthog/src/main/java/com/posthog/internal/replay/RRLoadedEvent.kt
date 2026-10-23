package com.posthog.internal.replay

internal class RRLoadedEvent(timestamp: Long) : RREvent(
    type = RREventType.Load,
    timestamp = timestamp,
)
