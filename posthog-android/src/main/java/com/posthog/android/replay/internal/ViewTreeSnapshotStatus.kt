package com.posthog.android.replay.internal

import com.posthog.internal.RRWireframe

internal data class ViewTreeSnapshotStatus(
    val listener: NextDrawListener,
    var sentFullSnapshot: Boolean = false,
    var lastSnapshot: RRWireframe? = null,
)
