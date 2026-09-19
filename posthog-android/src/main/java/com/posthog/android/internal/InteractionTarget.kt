package com.posthog.android.internal

import android.graphics.Matrix
import android.view.View
import android.view.ViewGroup
import com.posthog.android.R
import java.lang.ref.WeakReference
import java.util.Locale

internal const val MAX_INTERACTION_DEPTH = 32
internal const val MAX_INTERACTION_NODES = 512
internal const val MAX_INTERACTION_ELEMENTS = 20

/** Only this allowlist is serialized. Never add labels, values or arbitrary attributes. */
internal data class InteractionElement(
    val type: String,
    val identifier: String? = null,
    val nthChild: Int = 1,
    val nthOfType: Int = 1,
) {
    fun properties(): Map<String, Any> =
        buildMap {
            put("tag_name", type)
            identifier?.take(128)?.takeIf { it.isNotEmpty() }?.let { put("attr__id", it) }
            put("nth_child", nthChild)
            put("nth_of_type", nthOfType)
        }
}

internal fun interactionProperties(
    elements: List<InteractionElement>,
    touchX: Float,
    touchY: Float,
): Map<String, Any> {
    val bounded = elements.take(MAX_INTERACTION_ELEMENTS).map { it.properties() }

    // Match posthog-js e96852d: attr__id is retained AND normalized to attr_id.
    // Its quote escape is deliberately idempotent for an already escaped quote.
    fun escape(value: Any): String = value.toString().replace(Regex("\\\\?\"")) { "\\\"" }
    val chain =
        bounded.joinToString(";") { element ->
            val attributes =
                sortedMapOf<String, Any>(
                    "nth-child" to element.getValue("nth_child"),
                    "nth-of-type" to element.getValue("nth_of_type"),
                )
            element["attr__id"]?.let {
                attributes["attr__id"] = it
                attributes["attr_id"] = it
            }
            element.getValue("tag_name").toString() + ":" +
                attributes.entries.joinToString("") { (key, value) -> "$key=\"${escape(value)}\"" }
        }
    return mapOf(
        "\$event_type" to "touch",
        "\$touch_x" to touchX,
        "\$touch_y" to touchY,
        "\$ce_version" to 1,
        "\$elements" to bounded,
        "\$elements_chain" to chain,
    )
}

/** Weak identity stays local; only immutable, allowlisted properties leave the UI thread. */
internal class InteractionTarget(
    view: View,
    val semanticsId: Int? = null,
    val elements: List<InteractionElement>,
    val repetitive: Boolean = false,
) {
    val view = WeakReference(view)

    fun sameTarget(other: InteractionTarget): Boolean =
        view.get() != null && view.get() === other.view.get() && semanticsId == other.semanticsId
}

internal class InteractionTargetResolver(
    private val composeAvailable: Boolean = isInteractionComposeAvailable(),
) {
    fun resolve(
        root: View,
        screenX: Float,
        screenY: Float,
    ): InteractionTarget? {
        val location = IntArray(2)
        root.getLocationOnScreen(location)
        var visited = 0
        var incomplete = false

        fun hit(
            view: View,
            x: Float,
            y: Float,
            depth: Int,
        ): List<View>? {
            if (++visited > MAX_INTERACTION_NODES || depth >= MAX_INTERACTION_DEPTH) {
                incomplete = true
                return null
            }
            if (view.visibility != View.VISIBLE || view.alpha <= 0f) return null
            view.clipBounds?.let { if (x < it.left || y < it.top || x >= it.right || y >= it.bottom) return null }
            val inside = x >= 0 && y >= 0 && x < view.width && y < view.height
            if (view is ViewGroup) {
                if (view.clipChildren && !inside) return null
                val hasPadding = view.paddingLeft != 0 || view.paddingTop != 0 || view.paddingRight != 0 || view.paddingBottom != 0
                if (view.clipToPadding && hasPadding && (
                        x < view.paddingLeft || y < view.paddingTop ||
                            x >= view.width - view.paddingRight || y >= view.height - view.paddingBottom
                    )
                ) {
                    // Padding clips children, not the parent's own clickable background.
                    return if (inside) listOf(view) else null
                }
                if (view.childCount > MAX_INTERACTION_NODES - visited) {
                    incomplete = true
                    return null
                }
                // Reverse paint order, with elevation taking precedence over sibling order.
                val children = (0 until view.childCount).map { view.getChildAt(it) }.sortedBy { it.z }
                for (child in children.asReversed()) {
                    val point = floatArrayOf(x + view.scrollX - child.left, y + view.scrollY - child.top)
                    if (!child.matrix.isIdentity) {
                        val inverse = Matrix()
                        if (!child.matrix.invert(inverse)) continue
                        inverse.mapPoints(point)
                    }
                    hit(child, point[0], point[1], depth + 1)?.let { return listOf(view) + it }
                    if (incomplete) return null
                }
            }
            return if (inside) listOf(view) else null
        }
        val path = hit(root, screenX - location[0], screenY - location[1], 0) ?: return null
        if (incomplete) return null
        val targetIndex = path.indexOfLast { it.isClickable && it.isEnabled }

        fun nativeTarget(): InteractionTarget? {
            if (targetIndex < 0 || path.any { it.isInteractionIgnored() }) return null
            if (path.drop(targetIndex).any { it.isClickable && !it.isEnabled }) return null
            val targetPath = path.take(targetIndex + 1)
            return InteractionTarget(
                targetPath.last(),
                elements = targetPath.asReversed().take(MAX_INTERACTION_ELEMENTS).map { it.interactionElement() },
                repetitive =
                    targetPath.last().let {
                        it is android.widget.EditText || it is android.widget.AbsSeekBar || it is android.widget.NumberPicker ||
                            it is android.widget.AbsListView || it is android.widget.ScrollView || it is android.widget.HorizontalScrollView
                    },
            )
        }
        val composeHosts =
            if (composeAvailable) {
                path.indices.filter { path[it].javaClass.name == ANDROID_COMPOSE_VIEW_CLASS_NAME }
            } else {
                emptyList()
            }
        // Resolving only the inner host cannot prove that outer Compose exclusions were respected.
        if (composeHosts.size > 1) return null
        val composeIndex = composeHosts.singleOrNull() ?: -1
        if (composeIndex >= 0) {
            // AndroidViewsHandler and rendering layers can cover the entire Compose host. They
            // must not hide semantic targets, and native interop still needs Compose exclusions.
            if (path.take(composeIndex + 1).any { it.isInteractionIgnored() }) return null
            if (path[composeIndex].isClickable && !path[composeIndex].isEnabled) return null
            return ComposeInteractionTargetResolver.resolve(
                path[composeIndex],
                screenX,
                screenY,
                path.take(composeIndex + 1),
                nativeTarget = nativeTarget(),
                nativeLayoutInfo = ComposeInteractionTargetResolver.interopLayoutInfo(path.drop(composeIndex + 1)),
            )
        }
        return nativeTarget()
    }
}

internal fun View.isInteractionIgnored(): Boolean =
    getTag(R.id.posthog_autocapture_no_capture) == true ||
        (tag as? String)?.contains("ph-no-capture", ignoreCase = true) == true ||
        contentDescription?.contains("ph-no-capture", ignoreCase = true) == true

internal fun View.interactionElement(): InteractionElement {
    val type = javaClass.simpleName.lowercase(Locale.ROOT).take(128).ifEmpty { "view" }
    val parent = parent as? ViewGroup
    var nthChild = 1
    var nthOfType = 1
    if (parent != null) {
        // Huge sibling sets cannot be safely enumerated on the input path.
        if (parent.childCount > MAX_INTERACTION_NODES) throw IllegalStateException("Interaction sibling limit")
        for (i in 0 until parent.childCount) {
            val sibling = parent.getChildAt(i)
            if (sibling === this) break
            nthChild++
            if (sibling.javaClass == javaClass) nthOfType++
        }
    }
    val identifier =
        if (id != View.NO_ID) {
            try {
                resources.getResourceEntryName(id)
            } catch (_: android.content.res.Resources.NotFoundException) {
                null
            }
        } else {
            null
        }
    return InteractionElement(type, identifier, nthChild, nthOfType)
}

private fun isInteractionComposeAvailable(): Boolean =
    try {
        // Its name is already preserved by the replay consumer rules.
        Class.forName(ANDROID_COMPOSE_VIEW_CLASS_NAME, false, InteractionTargetResolver::class.java.classLoader)
        true
    } catch (_: Throwable) {
        false
    }
