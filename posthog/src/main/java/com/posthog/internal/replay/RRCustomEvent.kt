package com.posthog.internal.replay

import com.posthog.PostHogInternal

@PostHogInternal
public class RRCustomEvent(tag: String, payload: Any, timestamp: Long) : RREvent(
    type = RREventType.Custom,
    data =
        mapOf(
            "tag" to tag,
            "payload" to payload,
        ),
    timestamp = timestamp,
)
