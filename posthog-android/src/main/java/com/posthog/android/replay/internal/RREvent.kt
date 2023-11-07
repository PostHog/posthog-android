package com.posthog.android.replay.internal

internal data class RREvent(
    val timestamp: Long,
    val type: Int,
    val data: Map<String, Any>? = null,
)
