package com.posthog.internal.replay

import com.posthog.PostHog
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
    PostHog.capture("\$snapshot", properties = properties)
}

@PostHogInternal
public fun List<RREvent>.capture(postHog: PostHogInterface? = null) {
    val properties =
        mutableMapOf(
            "\$snapshot_data" to this,
            "\$snapshot_source" to "mobile",
        )
    postHog?.capture("\$snapshot", properties = properties)
}
