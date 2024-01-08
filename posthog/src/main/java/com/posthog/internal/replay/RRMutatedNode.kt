package com.posthog.internal.replay

import com.posthog.PostHogInternal

@PostHogInternal
public class RRMutatedNode(
    public val wireframe: RRWireframe,
    public val parentId: Int? = null,
)
