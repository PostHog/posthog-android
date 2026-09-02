package com.posthog.internal.replay

import com.posthog.PostHog
import com.posthog.PostHogEventName
import com.posthog.PostHogInterface
import com.posthog.PostHogInternal

// used by react native and flutter with the static instance
@PostHogInternal
public fun List<RREvent>.capture() {
    captureReplayEvents()
}

@PostHogInternal
public fun List<RREvent>.capture(postHog: PostHogInterface? = null) {
    captureReplayEvents(postHog)
}

@PostHogInternal
public fun List<RREvent>.captureInWindow(
    windowId: String,
    postHog: PostHogInterface? = null,
) {
    captureReplayEvents(postHog, windowId.takeIf { it.isNotBlank() })
}

private fun List<RREvent>.captureReplayEvents(
    postHog: PostHogInterface? = null,
    windowId: String? = null,
) {
    val properties =
        mutableMapOf<String, Any>(
            "\$snapshot_data" to this,
            "\$snapshot_source" to "mobile",
        )
    windowId?.let { properties["\$window_id"] = it }

    // its not guaranteed that the posthog instance is set
    (postHog ?: PostHog).capture(PostHogEventName.SNAPSHOT.event, properties = properties)
}
