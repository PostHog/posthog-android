package com.posthog.android

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics

/** Compose exclusions for automatic element interaction capture. */
public object PostHogAutocaptureModifier {
    internal val PostHogAutocaptureIgnore = SemanticsPropertyKey<Boolean>("PostHogAutocaptureIgnore")

    /**
     * Excludes this element and its descendants from automatic interaction events.
     * Changes to [isEnabled] apply to subsequent interactions. Does not change session replay masking.
     *
     * @param isEnabled Whether to exclude the subtree. Defaults to true; false has no effect.
     */
    public fun Modifier.postHogAutocaptureIgnore(isEnabled: Boolean = true): Modifier =
        semantics { this[PostHogAutocaptureIgnore] = isEnabled }
}
