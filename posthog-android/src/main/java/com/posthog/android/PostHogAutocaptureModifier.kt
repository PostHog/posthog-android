package com.posthog.android

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics

internal val PostHogAutocaptureNoCapture = SemanticsPropertyKey<Boolean>("PostHogAutocaptureNoCapture")

/**
 * Excludes this element and its descendants from automatic interaction events.
 * Changes to [isEnabled] apply to subsequent interactions. Does not change session replay masking.
 *
 * @param isEnabled Whether to exclude the subtree. Defaults to true; false has no effect.
 */
public fun Modifier.postHogAutocaptureNoCapture(isEnabled: Boolean = true): Modifier =
    semantics { this[PostHogAutocaptureNoCapture] = isEnabled }
