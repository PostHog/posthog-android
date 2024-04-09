package com.posthog.internal.replay

import com.posthog.PostHogInternal

@PostHogInternal
public class RRIncrementalMouseInteractionData(
    public val id: Int,
    public val type: RRMouseInteraction,
    public val x: Int,
    public val y: Int,
    public val source: RRIncrementalSource = RRIncrementalSource.MouseInteraction,
    // always Touch
    public val pointerType: Int = 2,
    public val positions: List<RRMousePosition>? = null,
)
