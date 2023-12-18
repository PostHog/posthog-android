package com.posthog.internal.replay

import com.posthog.PostHog
import com.posthog.PostHogInternal

@PostHogInternal
public fun List<RREvent>.capture() {
    val properties = mutableMapOf(
        "\$snapshot_data" to this,
        "\$snapshot_source" to "mobile",
    )
    PostHog.capture("\$snapshot", properties = properties)
}
