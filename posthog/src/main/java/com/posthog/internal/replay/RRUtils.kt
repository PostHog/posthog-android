package com.posthog.internal.replay

import com.posthog.PostHog
import com.posthog.PostHogInternal

@PostHogInternal
public enum class PostHogSessionReplayMode(public val mode: String) {
    WIREFRAME("wireframe"),
    SCREENSHOT("screenshot")
}

@PostHogInternal
public fun List<RREvent>.capture(mode: PostHogSessionReplayMode = PostHogSessionReplayMode.WIREFRAME) {
    val properties =
        mutableMapOf(
            "\$snapshot_data" to this,
            "\$snapshot_source" to "mobile",
            "\$snapshot_mode" to mode.mode
        )
    PostHog.capture("\$snapshot", properties = properties)
}
