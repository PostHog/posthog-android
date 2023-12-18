package com.posthog.internal.replay

import com.posthog.PostHogInternal

@PostHogInternal
public class RRLoadedEvent(timestamp: Long) : RREvent(
    type = RREventType.Load,
    timestamp = timestamp,
)
