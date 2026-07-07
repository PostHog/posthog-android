package com.posthog.android.replay.internal

import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar

// Bounds the recursion against a pathological or cyclic view hierarchy (see #281). A depth cap,
// rather than the identity visited-set findMaskableWidgets uses, because this walk runs from the
// draw path and a real Android view tree can't cycle — so it avoids a per-node set allocation.
private const val MAX_PROGRESS_BAR_SEARCH_DEPTH = 100

/**
 * Returns the first visible, non-transparent, laid-out indeterminate [ProgressBar] (e.g. a loading
 * spinner) in this view's subtree, or null. Such widgets invalidate their drawable every frame
 * without ever calling setHasTransientState, so they are invisible to [View.hasTransientState].
 * Their redraws keep view geometry stable, so treating them as animation-only lets a continuously
 * spinning loader still be captured without misaligning masks.
 */
internal fun View.findRunningIndeterminateProgressBar(depth: Int = 0): View? {
    if (depth > MAX_PROGRESS_BAR_SEARCH_DEPTH) return null
    // Prune subtrees the user can't see: an invisible or fully transparent spinner isn't driving a
    // visible animation, so it must not relax the mask-alignment guard. Checked at every level so an
    // invisible/alpha-0 ancestor hides its descendants too.
    if (visibility != View.VISIBLE || alpha <= 0f) return null
    if (this is ProgressBar) {
        return if (isIndeterminate && width > 0 && height > 0) this else null
    }
    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            val child = getChildAt(i) ?: continue
            child.findRunningIndeterminateProgressBar(depth + 1)?.let { return it }
        }
    }
    return null
}

/**
 * Cheap re-check of a single, already-located spinner (O(tree depth) via [View.isShown] rather than
 * O(tree)): whether it is still an attached, shown, non-transparent, laid-out indeterminate bar.
 * Lets a tracked spinner be re-validated every frame without re-walking the whole tree. Keep the
 * "indeterminate + visible + laid out" rule here in sync with [findRunningIndeterminateProgressBar].
 */
internal fun View.isShownIndeterminateProgressBar(): Boolean =
    this is ProgressBar && isIndeterminate && isShown && alpha > 0f && width > 0 && height > 0
