package com.posthog.internal.replay

import com.posthog.PostHogInternal

@PostHogInternal
public class RRDomContentLoadedEvent(timestamp: Long) : RREvent(
    type = RREventType.DomContentLoaded,
    timestamp = timestamp,
)
