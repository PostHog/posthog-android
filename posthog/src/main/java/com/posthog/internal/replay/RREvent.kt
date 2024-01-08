package com.posthog.internal.replay

import com.posthog.PostHogInternal
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

@IgnoreJRERequirement
@PostHogInternal
public open class RREvent(
    public val type: RREventType,
    public val timestamp: Long,
    public val data: Any? = null,
)
