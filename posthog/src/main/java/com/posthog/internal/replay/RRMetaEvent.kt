package com.posthog.internal.replay

import com.posthog.PostHogInternal

@PostHogInternal
public class RRMetaEvent(width: Int, height: Int, timestamp: Long, href: String) : RREvent(
    type = RREventType.Meta,
    data =
        mapOf(
            "href" to href,
            "width" to width,
            "height" to height,
        ),
    timestamp = timestamp,
)
