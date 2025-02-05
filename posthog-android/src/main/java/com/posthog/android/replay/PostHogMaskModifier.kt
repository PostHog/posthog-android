package com.posthog.android.replay

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import com.posthog.android.replay.PostHogReplayIntegration.Companion.PH_NO_CAPTURE_LABEL

public object PostHogMaskModifier {
    internal val PostHogReplayMask = SemanticsPropertyKey<Boolean>(PH_NO_CAPTURE_LABEL)

    /**
     * Modifier to mask or unmask elements in the session replay.
     * @param isEnabled If true, the element will be masked in the session replay.
     * If false, the element will be unmasked in the session replay.
     * This will override the defaults like maskAllTextInputs, maskAllImages etc. when used with the respective elements.
     */
    public fun Modifier.postHogMask(isEnabled: Boolean = true): Modifier {
        return semantics(
            properties = {
                this[PostHogReplayMask] = isEnabled
            },
        )
    }
}
