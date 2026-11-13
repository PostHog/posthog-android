package com.posthog.internal.replay

internal class RRDocumentNode(tag: String, payload: Any, timestamp: Long) : RREvent(
    type = RREventType.Custom,
    data =
        mapOf(
            "tag" to tag,
            "payload" to payload,
        ),
    timestamp = timestamp,
)
