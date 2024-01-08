package com.posthog.internal.replay

import com.posthog.PostHogInternal

@PostHogInternal
public class RRIncrementalMutationData(
    public val adds: List<RRMutatedNode>? = null,
    public val removes: List<RRRemovedNode>? = null,
    // updates and adds share the same format
    public val updates: List<RRMutatedNode>? = null,
    public val source: RRIncrementalSource = RRIncrementalSource.Mutation,
)
