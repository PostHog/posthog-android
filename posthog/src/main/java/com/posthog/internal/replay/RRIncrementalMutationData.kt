package com.posthog.internal.replay

import com.posthog.PostHogInternal

@PostHogInternal
public class RRIncrementalMutationData(
    public val adds: List<RRAddedNode>? = null,
    public val removes: List<RRRemovedNode>? = null,
    public val source: RRIncrementalSource = RRIncrementalSource.Mutation,
    // TODO: mutations/updates pending
)
