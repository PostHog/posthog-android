package com.posthog.android.replay

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import com.posthog.android.replay.PostHogReplayIntegration.Companion.PH_NO_CAPTURE_LABEL
import com.posthog.android.replay.PostHogReplayIntegration.Companion.PH_NO_MASK_LABEL

public object PostHogMaskModifier {
    internal val PostHogReplayMask = SemanticsPropertyKey<Boolean>(PH_NO_CAPTURE_LABEL)
    internal val PostHogReplayUnmask = SemanticsPropertyKey<Boolean>(PH_NO_MASK_LABEL)

    /**
     * Modifier to mask elements in the session replay.
     * @param isEnabled If true, the modifier takes effect and the element will be masked in the session replay.
     * If false, the modifier has no effect, as if it was never applied.
     * This is useful for controlling masking dynamically via a global setting or remote config
     * without having to add or remove the modifier from the composable tree.
     */
    public fun Modifier.postHogMask(isEnabled: Boolean = true): Modifier {
        return semantics(
            properties = {
                this[PostHogReplayMask] = isEnabled
            },
        )
    }

    /**
     * Modifier to explicitly unmask elements in the session replay.
     * When applied, the element will NOT be masked even if maskAllTextInputs or maskAllImages is enabled.
     * This modifier takes precedence over the global masking configuration and [postHogMask].
     * @param isEnabled If true, the modifier takes effect and the element will not be masked in the session replay.
     * If false, the modifier has no effect, as if it was never applied.
     * This is useful for controlling unmasking dynamically via a global setting or remote config
     * without having to add or remove the modifier from the composable tree.
     */
    public fun Modifier.postHogUnmask(isEnabled: Boolean = true): Modifier {
        return semantics(
            properties = {
                this[PostHogReplayUnmask] = isEnabled
            },
        )
    }
}
