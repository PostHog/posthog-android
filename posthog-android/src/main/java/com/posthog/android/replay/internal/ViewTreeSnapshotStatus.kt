package com.posthog.android.replay.internal

import com.posthog.internal.replay.RRWireframe

internal class ViewTreeSnapshotStatus(
    val listener: NextDrawListener,
    var sentFullSnapshot: Boolean = false,
    var sentMetaEvent: Boolean = false,
    var keyboardOpen: Boolean = false,
    var lastSnapshot: RRWireframe? = null,
)
