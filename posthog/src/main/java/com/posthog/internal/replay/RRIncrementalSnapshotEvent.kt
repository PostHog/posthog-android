package com.posthog.internal.replay

import com.posthog.PostHogInternal

@PostHogInternal
public class RRIncrementalSnapshotEvent(
    mutationData: RRIncrementalMutationData? = null,
    timestamp: Long,
) : RREvent(
        type = RREventType.IncrementalSnapshot,
        data = mutationData,
        timestamp = timestamp,
    )
