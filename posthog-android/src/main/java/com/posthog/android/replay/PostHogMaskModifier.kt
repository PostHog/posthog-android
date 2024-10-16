package com.posthog.android.replay

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics

public object PostHogMaskModifier {
    internal val PostHogReplayMask = SemanticsPropertyKey<Boolean>("ph-no-capture")

    /**
     * Marks the element as not to be captured by PostHog Session Replay.
     */
    public fun Modifier.postHogMaskReplay(): Modifier {
        return semantics(
            properties = {
                this[PostHogReplayMask] = true
            },
        )
    }
}
