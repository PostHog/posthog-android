package com.posthog.android.replay.internal

import android.graphics.Rect
import com.posthog.android.internal.densityValue
import com.posthog.internal.replay.RRStyle
import com.posthog.internal.replay.RRWireframe

/**
 * The subset of a Compose `SemanticsNode` the wireframe renderer needs.
 *
 * Semantics can only be read on the main thread, so reading the tree and turning it into
 * wireframes are two separate steps: the reader hops to the main thread and fills these in,
 * the mapping below runs on the capture thread. Keeping the mapping off the Compose types
 * also means the masking rules can be tested without a Compose runtime on the classpath.
 */
internal data class ComposeSemanticsNode(
    /** `SemanticsNode.id`, stable across recompositions for the same layout node. */
    val id: Int,
    /** `boundsInWindow`, in pixels. */
    val bounds: Rect,
    val text: String? = null,
    val isEditable: Boolean = false,
    val isPassword: Boolean = false,
    val contentDescription: String? = null,
    val role: ComposeRole = ComposeRole.None,
    val checked: Boolean? = null,
    /** `postHogMask` is active on this node or an ancestor. */
    val maskForced: Boolean = false,
    /** `postHogUnmask` is active on this node or an ancestor, and wins over everything else. */
    val unmaskForced: Boolean = false,
)

/**
 * Mirror of the Compose `Role`s that map onto a wireframe type. Only the roles that exist in
 * every supported Compose version are listed — the reader falls back to [None] for the rest,
 * which still renders as text.
 */
internal enum class ComposeRole {
    None,
    Button,
    Checkbox,
    Switch,
    RadioButton,
    Tab,
    Image,
}

/**
 * Converts a flattened (merged) Compose semantics tree into wireframes.
 *
 * Compose draws its whole UI into a single `AndroidComposeView` with no child Views, so without
 * this the wireframe tree bottoms out at that view and the player has nothing but a background
 * colour to render. Wireframe positions are absolute, so the nodes are emitted as a flat list of
 * children rather than a nested tree.
 *
 * Nodes that carry no visual information (layout containers, nodes with empty bounds) are skipped:
 * wireframe mode only ships what the SDK understands, and an empty rectangle would just add noise.
 */
internal fun List<ComposeSemanticsNode>.toWireframes(
    hostViewId: Int,
    parentId: Int,
    density: Float,
    maskAllTextInputs: Boolean,
    ancestorUnmasked: Boolean = false,
): List<RRWireframe> {
    val wireframes = mutableListOf<RRWireframe>()

    for (node in this) {
        if (node.bounds.width() <= 0 || node.bounds.height() <= 0) {
            continue
        }

        // kept in sync with the screenshot path (findMaskableComposeWidgets) and with the View path
        // (shouldMaskTextView): postHogUnmask wins over everything, then postHogMask and passwords
        // force masking, then the maskAllTextInputs config applies. Images need no rule of their
        // own — semantics carries no pixels, so they are never captured either way.
        val unmasked = ancestorUnmasked || node.unmaskForced
        val maskText = !unmasked && (node.maskForced || node.isPassword || maskAllTextInputs)

        val text = node.text?.takeUnless { it.isEmpty() }?.let { if (maskText) it.mask() else it }
        val isImage = node.role == ComposeRole.Image || (text == null && node.contentDescription != null)

        var type: String? = null
        var inputType: String? = null
        var wireframeText: String? = null
        var value: Any? = null
        var label: String? = null
        var checked: Boolean? = null

        when {
            isImage -> {
                // no base64, so the player draws its image placeholder — the same thing a masked
                // ImageView produces on the View path
                type = "image"
            }

            node.role == ComposeRole.Button -> {
                type = "input"
                inputType = "button"
                value = text
            }

            node.role == ComposeRole.Checkbox -> {
                type = "input"
                inputType = "checkbox"
                label = text
                checked = node.checked
            }

            node.role == ComposeRole.RadioButton || node.role == ComposeRole.Tab -> {
                type = "input"
                inputType = "radio"
                label = text
                checked = node.checked
            }

            node.role == ComposeRole.Switch -> {
                type = "input"
                inputType = "toggle"
                label = text
                checked = node.checked
            }

            node.isEditable -> {
                type = "input"
                inputType = "text_area"
                value = text
            }

            text != null -> {
                type = "text"
                wireframeText = text
            }

            else -> {
                // no text, no image, no role: nothing to draw
                continue
            }
        }

        val style = RRStyle()
        if (type == "input" && inputType == "button") {
            style.borderWidth = 1
            style.borderColor = "#000000"
        }
        if (type == "text") {
            style.verticalAlign = "center"
            style.horizontalAlign = "left"
        }

        wireframes.add(
            RRWireframe(
                id = composeWireframeId(hostViewId, node.id),
                x = node.bounds.left.densityValue(density),
                y = node.bounds.top.densityValue(density),
                width = node.bounds.width().densityValue(density),
                height = node.bounds.height().densityValue(density),
                type = type,
                inputType = inputType,
                text = wireframeText,
                value = value,
                label = label,
                checked = checked,
                style = style,
                parentId = parentId,
            ),
        )
    }

    return wireframes
}

/**
 * Semantics ids are small integers scoped to their composition root, while View wireframe ids come
 * from `System.identityHashCode`. Snapshots are diffed purely by id, so the semantics id is mixed
 * with the host view's id to stay unique within a snapshot while remaining stable across snapshots.
 */
internal fun composeWireframeId(
    hostViewId: Int,
    semanticsId: Int,
): Int {
    // golden-ratio constant, spreads the small semantics ids over the whole int range
    return hostViewId xor (semanticsId * -0x61c88647)
}

private fun String.mask(): String {
    return "*".repeat(length)
}
