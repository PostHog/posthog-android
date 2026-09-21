package com.posthog.android.internal

import android.view.View
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.posthog.android.PostHogAutocaptureNoCapture
import com.posthog.android.replay.PostHogMaskModifier.PostHogReplayMask

/** Called only for a verified Compose host; never stores semantics configurations or strings. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal object ComposeInteractionResponseSnapshot {
    fun append(
        view: View,
        sink: InteractionResponseDigest,
        depth: Int,
    ): Set<LayoutInfo> {
        val owner = (view as? RootForTest)?.semanticsOwner ?: error("Unsupported semantics")
        val excludedLayouts = mutableSetOf<LayoutInfo>()

        fun visit(
            node: SemanticsNode,
            level: Int,
            excluded: Boolean,
        ) {
            sink.node(level)
            sink.number(node.id)
            val bounds = node.boundsInWindow
            sink.number(bounds.left.toBits())
            sink.number(bounds.top.toBits())
            sink.number(bounds.right.toBits())
            sink.number(bounds.bottom.toBits())
            val config = node.config
            val ignored =
                excluded || config.getOrNull(PostHogAutocaptureNoCapture) == true ||
                    config.getOrNull(PostHogReplayMask) == true || config.contains(SemanticsProperties.Password) ||
                    config.contains(SemanticsActions.SetText) || config.contains(SemanticsProperties.EditableText)
            if (ignored) excludedLayouts += node.layoutInfo
            sink.number(if (ignored) 1 else 0)
            sink.number(if (config.contains(SemanticsProperties.Disabled)) 1 else 0)
            sink.number(if (config.contains(SemanticsProperties.InvisibleToUser)) 1 else 0)
            if (!ignored) {
                config.getOrNull(SemanticsProperties.Text)?.forEach { sink.text(it.text) }
                config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { sink.text(it) }
                sink.text(config.getOrNull(SemanticsProperties.StateDescription))
                sink.number(if (config.getOrNull(SemanticsProperties.Selected) == true) 1 else 0)
                sink.number(if (config.getOrNull(SemanticsProperties.Focused) == true) 1 else 0)
                sink.number(config.getOrNull(SemanticsProperties.ToggleableState)?.ordinal ?: -1)
                config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.let { sink.number(it.current.toBits()) }
                config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.let { sink.number(it.value().toBits()) }
                config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { sink.number(it.value().toBits()) }
            }
            val children = node.children
            check(children.size <= MAX_INTERACTION_NODES - sink.nodes)
            children.forEach { visit(it, level + 1, ignored) }
        }
        visit(owner.unmergedRootSemanticsNode, depth, false)
        return excludedLayouts
    }
}
