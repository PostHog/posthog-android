package com.posthog.android.replay

import android.graphics.Rect
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.layout.boundsInWindow
import java.lang.reflect.Field
import java.lang.reflect.Method

// Image and Coil's AsyncImage add no semantics when contentDescription is null, so the semantics
// tree cannot find them. Walk the layout tree and find the painter modifiers instead.
// Only call on the main thread: the caches below are not synchronized.

// The internal name of LayoutNode.children depends on the Compose version.
private val layoutNodeChildrenNames = listOf("getChildren\$ui", "getChildren\$ui_release", "getChildrenInfo")
private var layoutNodeChildren: Method? = null

// Keyed by modifier class. A null value means that the class holds no Painter field.
private val painterFields = HashMap<Class<*>, Field?>()

/**
 * Adds the window bounds of each painter-backed layout node below [root] to [rects].
 * Skips the subtree of each node in [unmaskedLayouts].
 * Throws if Compose internals are not available, so that the caller can fail closed.
 */
internal fun collectComposeImageRects(
    root: LayoutInfo,
    unmaskedLayouts: Set<LayoutInfo>,
    rects: MutableList<Rect>,
) {
    val children = layoutNodeChildren ?: root.javaClass.findChildrenMethod().also { layoutNodeChildren = it }
    val stack = ArrayDeque<LayoutInfo>()
    stack.addLast(root)
    while (stack.isNotEmpty()) {
        val node = stack.removeLast()
        if (node in unmaskedLayouts || !node.isPlaced) {
            continue
        }
        if (node.hasMaskablePainter()) {
            val coordinates = node.coordinates
            if (coordinates.isAttached) {
                val bounds = coordinates.boundsInWindow()
                if (bounds.width > 0 && bounds.height > 0) {
                    rects.add(Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt()))
                }
            }
        }
        (children.invoke(node) as List<*>).forEach { child ->
            (child as? LayoutInfo)?.let { stack.addLast(it) }
        }
    }
}

private fun Class<*>.findChildrenMethod(): Method {
    for (name in layoutNodeChildrenNames) {
        try {
            return getMethod(name)
        } catch (_: NoSuchMethodException) {
        }
    }
    throw NoSuchMethodException("$name has no children accessor")
}

private fun LayoutInfo.hasMaskablePainter(): Boolean =
    getModifierInfo().any { info ->
        val modifier = info.modifier
        val field = modifier.javaClass.painterField() ?: return@any false
        (field.get(modifier) as? Painter).shouldMaskPainter()
    }

private fun Class<*>.painterField(): Field? {
    if (painterFields.containsKey(this)) {
        return painterFields[this]
    }
    val field =
        declaredFields.firstOrNull { Painter::class.java.isAssignableFrom(it.type) }?.apply { isAccessible = true }
    painterFields[this] = field
    return field
}

// Same rule as Drawable.shouldMaskDrawable: vectors and plain fills are not images.
private fun Painter?.shouldMaskPainter(): Boolean =
    when {
        this == null -> false
        this is VectorPainter || this is ColorPainter -> false
        javaClass.name == "androidx.compose.ui.graphics.painter.BrushPainter" -> false
        else -> true
    }
