package com.posthog

import java.util.Date

public data class PostHogEvent(
    val event: String,
    val properties: Map<String, Any>,
    val timestamp: Date = Date(),
)
