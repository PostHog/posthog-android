package com.posthog.internal

import com.posthog.PostHogInternal
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

@IgnoreJRERequirement
@PostHogInternal
public open class PostHogRemoteConfigResponse(
    // its either a boolean or a map, see https://github.com/PostHog/posthog-js/blob/10fd7f4fa083f997d31a4a4c7be7d311d0a95e74/src/types.ts#L235-L243
    public val sessionRecording: Any? = false,
    // its either a boolean or a map
    public val surveys: Any? = false,
    // Indicates if the team has any flags enabled (if not we don't need to load them)
    public val hasFeatureFlags: Boolean? = false,
)
