package com.posthog.internal

import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

@IgnoreJRERequirement
internal open class PostHogRemoteConfigResponse(
    // its either a boolean or a map, see https://github.com/PostHog/posthog-js/blob/10fd7f4fa083f997d31a4a4c7be7d311d0a95e74/src/types.ts#L235-L243
    val sessionRecording: Any? = false,
    // its eitger a boolean or a map
    val surveys: Any? = false,
    // Indicates if the team has any flags enabled (if not we don't need to load them)
    val hasFeatureFlags: Boolean? = false,
)
