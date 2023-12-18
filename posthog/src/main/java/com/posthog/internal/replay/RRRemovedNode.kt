package com.posthog.internal.replay

import com.posthog.PostHogInternal

@PostHogInternal
public class RRRemovedNode(
    public val id: Int,
    public val parentId: Int? = null,
)
