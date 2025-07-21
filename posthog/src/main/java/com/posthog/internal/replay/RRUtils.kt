package com.posthog.internal.replay

import com.posthog.PostHog
import com.posthog.PostHogEventName
import com.posthog.PostHogInterface
import com.posthog.PostHogInternal

// used by react native and flutter with the static instance
@PostHogInternal
public fun List<RREvent>.capture() {
    val properties =
        mutableMapOf(
            "\$snapshot_data" to this,
            "\$snapshot_source" to "mobile",
        )
    PostHog.capture(PostHogEventName.SNAPSHOT.event, properties = properties)
}

@PostHogInternal
public fun List<RREvent>.capture(postHog: PostHogInterface? = null) {
    val properties =
        mutableMapOf(
            "\$snapshot_data" to this,
            "\$snapshot_source" to "mobile",
        )

    // its not guaranteed that the posthog instance is set
    if (postHog != null) {
        postHog.capture(PostHogEventName.SNAPSHOT.event, properties = properties)
    } else {
        this.capture()
    }
}
