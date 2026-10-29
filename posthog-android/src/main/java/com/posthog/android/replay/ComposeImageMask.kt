package com.posthog.android.replay

import android.graphics.Rect
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.layout.boundsInWindow
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

// Image and Coil's AsyncImage add no semantics when contentDescription is null, so the semantics
// tree cannot find them. Walk the layout tree and find the painters instead.
// Only call on the main thread: the caches below are not synchronized.

// These internal names depend on the Compose version.
private val layoutNodeChildrenNames = listOf("getChildren\$ui", "getChildren\$ui_release", "getChildrenInfo")
private val layoutNodeNodesNames = listOf("getNodes\$ui", "getNodes\$ui_release")
private val nodeChainHeadNames = listOf("getHead\$ui", "getHead\$ui_release")
private val modifierNodeChildNames = listOf("getChild\$ui", "getChild\$ui_release")

private class NodeChainAccess(val nodes: Method, val head: Method, val child: Method)

private var layoutNodeChildren: Method? = null
private var nodeChainResolved = false
private var nodeChainAccess: NodeChainAccess? = null

// Keyed by class: the Painter fields it and its superclasses declare.
private val painterFields = HashMap<Class<*>, List<Field>>()

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
    val children = layoutNodeChildren ?: root.javaClass.findMethod(layoutNodeChildrenNames).also { layoutNodeChildren = it }
    if (!nodeChainResolved) {
        nodeChainAccess = root.javaClass.findNodeChainAccess()
        nodeChainResolved = true
    }
    val chain = nodeChainAccess
    val stack = ArrayDeque<LayoutInfo>()
    stack.addLast(root)
    while (stack.isNotEmpty()) {
        val node = stack.removeLast()
        if (node in unmaskedLayouts || !node.isPlaced) {
            continue
        }
        node.collectPainterRects(chain, rects)
        (children.invoke(node) as List<*>).forEach { child ->
            (child as? LayoutInfo)?.let { stack.addLast(it) }
        }
    }
}

private fun Class<*>.findMethodOrNull(names: List<String>): Method? {
    for (name in names) {
        try {
            return getMethod(name)
        } catch (_: NoSuchMethodException) {
        }
    }
    return null
}

private fun Class<*>.findMethod(names: List<String>): Method =
    findMethodOrNull(names) ?: throw NoSuchMethodException("$name has none of $names")

// Compose before 1.3 has no Modifier.Node, so painters are on the modifiers only. Once it exists,
// a missing accessor means the internals moved, so throw to fail closed.
private fun Class<*>.findNodeChainAccess(): NodeChainAccess? {
    val nodes =
        findMethodOrNull(layoutNodeNodesNames)
            ?: if (hasModifierNode()) throw NoSuchMethodException("$name has none of $layoutNodeNodesNames") else return null
    val head = nodes.returnType.findMethod(nodeChainHeadNames)
    val child = head.returnType.findMethod(modifierNodeChildNames)
    return NodeChainAccess(nodes, head, child)
}

private fun hasModifierNode(): Boolean =
    try {
        Class.forName("androidx.compose.ui.Modifier\$Node")
        true
    } catch (_: ClassNotFoundException) {
        false
    }

// Same walk as NodeChain.getModifierInfo: the i-th ModifierInfo pairs with the i-th Modifier.Node
// from the head, and the tail node has no ModifierInfo. Old-style modifiers keep the painter on
// the modifier; Coil 3's AsyncImage and Glide keep it on the node only. Each match uses its own
// modifier's coordinates: a painter followed by padding also paints the padding.
private fun LayoutInfo.collectPainterRects(
    chain: NodeChainAccess?,
    rects: MutableList<Rect>,
) {
    var node = chain?.let { it.head.invoke(it.nodes.invoke(this)) }
    for (info in getModifierInfo()) {
        if (chain != null && node == null) {
            throw IllegalStateException("Compose modifier node chain is shorter than its modifiers")
        }
        if (info.modifier.holdsMaskablePainter() || node?.holdsMaskablePainter() == true) {
            val coordinates = info.coordinates
            if (coordinates.isAttached) {
                val bounds = coordinates.boundsInWindow()
                if (bounds.width > 0 && bounds.height > 0) {
                    rects.add(Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt()))
                }
            }
        }
        node = node?.let { chain?.child?.invoke(it) }
    }
}

private fun Any.holdsMaskablePainter(): Boolean =
    javaClass.painterFields().any { field -> (field.get(this) as? Painter).shouldMaskPainter() }

private fun Class<*>.painterFields(): List<Field> =
    painterFields.getOrPut(this) {
        generateSequence(this) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) && Painter::class.java.isAssignableFrom(it.type) }
            .onEach { it.isAccessible = true }
            .toList()
    }

// Same rule as Drawable.shouldMaskDrawable: vectors and plain fills are not images.
private fun Painter?.shouldMaskPainter(): Boolean =
    when {
        this == null -> false
        this is VectorPainter || this is ColorPainter -> false
        javaClass.name == "androidx.compose.ui.graphics.painter.BrushPainter" -> false
        else -> true
    }
