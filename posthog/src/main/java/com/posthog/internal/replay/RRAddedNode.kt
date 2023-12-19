package com.posthog.internal.replay

import com.posthog.PostHogInternal

// Also used for UpdatedNode
@PostHogInternal
public class RRAddedNode(
    public val wireframe: RRWireframe,
    public val parentId: Int? = null,
)
