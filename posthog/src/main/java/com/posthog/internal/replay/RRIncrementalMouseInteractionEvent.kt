package com.posthog.internal.replay

import com.posthog.PostHogInternal

@PostHogInternal
public class RRIncrementalMouseInteractionEvent(
    mouseInteractionData: RRIncrementalMouseInteractionData? = null,
    timestamp: Long,
) : RREvent(
        type = RREventType.IncrementalSnapshot,
        data = mouseInteractionData,
        timestamp = timestamp,
    )
