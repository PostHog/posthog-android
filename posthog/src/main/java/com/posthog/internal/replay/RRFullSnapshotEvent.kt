package com.posthog.internal.replay

import com.posthog.PostHogInternal

@PostHogInternal
public class RRFullSnapshotEvent(
    wireframes: List<RRWireframe>,
    initialOffsetTop: Int,
    initialOffsetLeft: Int,
    timestamp: Long,
) : RREvent(
        type = RREventType.FullSnapshot,
        data =
            mapOf(
                "wireframes" to wireframes,
                "initialOffset" to
                    mapOf(
                        "top" to initialOffsetTop,
                        "left" to initialOffsetLeft,
                    ),
            ),
        timestamp = timestamp,
    )
