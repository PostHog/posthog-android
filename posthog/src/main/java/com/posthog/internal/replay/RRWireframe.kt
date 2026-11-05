package com.posthog.internal.replay

import com.posthog.PostHogInternal
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

@IgnoreJRERequirement
@PostHogInternal
public data class RRWireframe(
    public val id: Int,
    public val x: Int,
    public val y: Int,
    public val width: Int,
    public val height: Int,
    // screenshot
    public val type: String? = null,
    public val base64: String? = null,
)
