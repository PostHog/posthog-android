package com.posthog.android.replay

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics

public object PostHogMaskModifier {
    internal val PostHogReplayMask = SemanticsPropertyKey<Boolean>("ph-no-capture")

    public fun Modifier.postHogMaskReplay(): Modifier {
        return semantics(
            properties = {
                this[PostHogReplayMask] = true
            },
        )
    }
}
