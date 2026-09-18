package com.posthog.android.internal

import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.posthog.android.PostHogAutocaptureNoCapture
import com.posthog.android.replay.PostHogMaskModifier.PostHogReplayMask

/** Loaded only after checking for Compose; uses unmerged semantics to preserve subtree exclusions. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal object ComposeInteractionTargetResolver {
    fun resolve(
        view: View,
        screenX: Float,
        screenY: Float,
        viewPath: List<View>,
        nativeTarget: InteractionTarget? = null,
        nativeLayoutInfo: LayoutInfo? = null,
    ): InteractionTarget? {
        val owner = (view as? RootForTest)?.semanticsOwner ?: return null
        val screen = IntArray(2)
        val window = IntArray(2)
        view.getLocationOnScreen(screen)
        view.getLocationInWindow(window)
        val point = Offset(screenX - screen[0] + window[0], screenY - screen[1] + window[1])
        var visited = 0
        var incomplete = false

        fun hit(
            node: SemanticsNode,
            depth: Int,
        ): List<SemanticsNode>? {
            if (++visited > MAX_INTERACTION_NODES || depth >= MAX_INTERACTION_DEPTH) {
                incomplete = true
                return null
            }
            val children = node.children
            if (children.size > MAX_INTERACTION_NODES - visited) {
                incomplete = true
                return null
            }
            // boundsInWindow is clipped by Compose. Children are in paint order.
            for (child in children.asReversed()) {
                hit(child, depth + 1)?.let { return listOf(node) + it }
                if (incomplete) return null
            }
            return if (node.boundsInWindow.contains(point)) listOf(node) else null
        }
        val path = hit(owner.unmergedRootSemanticsNode, 0) ?: return null
        if (incomplete ||
            path.any {
                it.config.getOrNull(PostHogAutocaptureNoCapture) == true ||
                    it.config.getOrNull(PostHogReplayMask) == true ||
                    it.config.contains(SemanticsProperties.Password)
            }
        ) {
            return null
        }
        if (nativeLayoutInfo != null && path.any { it.layoutInfo === nativeLayoutInfo }) return nativeTarget
        val nativeAncestor = nativeTarget?.view?.get()?.let { candidate -> viewPath.any { it === candidate } } == true
        if (nativeTarget != null && !nativeAncestor && nativeLayoutInfo == null) return null
        val targetIndex = path.indexOfLast { it.config.contains(SemanticsActions.OnClick) }
        if (targetIndex < 0) return nativeTarget.takeIf { nativeAncestor }
        if (path[targetIndex].config.contains(SemanticsProperties.Disabled)) return null
        val targetPath = path.take(targetIndex + 1)
        val elements =
            targetPath.asReversed().map { node ->
                val siblings = node.parent?.children ?: listOf(node)
                if (siblings.size > MAX_INTERACTION_NODES) return null
                val index = siblings.indexOfFirst { it.id == node.id }.coerceAtLeast(0)
                InteractionElement(
                    type = node.interactionType(),
                    identifier = node.config.getOrNull(SemanticsProperties.TestTag),
                    nthChild = index + 1,
                    nthOfType = siblings.take(index).count { it.interactionType() == node.interactionType() } + 1,
                )
            } + viewPath.asReversed().map { it.interactionElement() }
        return InteractionTarget(view, targetPath.last().id, elements.take(MAX_INTERACTION_ELEMENTS))
    }

    // The native child tree has no Compose paint order. Match its holder to the winning layout
    // before returning a native target, so an overlaid Compose control cannot be misattributed.
    fun interopLayoutInfo(path: List<View>): LayoutInfo? =
        try {
            val holderClass = Class.forName("androidx.compose.ui.viewinterop.AndroidViewHolder")
            val holder = path.lastOrNull { holderClass.isInstance(it) }
            holder?.let { holderClass.getMethod("getLayoutNode").invoke(it) as? LayoutInfo }
        } catch (_: Throwable) {
            null
        }

    private fun SemanticsNode.interactionType(): String =
        when (config.getOrNull(SemanticsProperties.Role)) {
            Role.Button -> "button"
            Role.Checkbox -> "checkbox"
            Role.Switch -> "switch"
            Role.RadioButton -> "radio"
            Role.Tab -> "tab"
            Role.Image -> "image"
            else -> "compose"
        }
}
