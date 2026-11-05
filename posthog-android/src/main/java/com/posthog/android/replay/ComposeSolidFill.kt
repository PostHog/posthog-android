package com.posthog.android.replay

import androidx.compose.ui.draw.DrawModifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

// These nodes are newer than our Compose compile floor, so look them up by name.
private val drawModifierNode by lazy(LazyThreadSafetyMode.NONE) {
    Class.forName("androidx.compose.ui.node.DrawModifierNode")
}
private val delegatingNode by lazy(LazyThreadSafetyMode.NONE) {
    Class.forName("androidx.compose.ui.node.DelegatingNode")
}
private val drawModifierNodeDraw by lazy(LazyThreadSafetyMode.NONE) {
    drawModifierNode.getMethod("draw", ContentDrawScope::class.java)
}
private val backwardsCompatNode by lazy(LazyThreadSafetyMode.NONE) {
    Class.forName("androidx.compose.ui.node.BackwardsCompatNode")
}
private val backwardsCompatNodeElement by lazy(LazyThreadSafetyMode.NONE) {
    backwardsCompatNode.getMethod("getElement")
}

private val drawBehindNode by lazy(LazyThreadSafetyMode.NONE) {
    Class.forName("androidx.compose.ui.draw.DrawBackgroundModifier")
}
private val layoutNode by lazy(LazyThreadSafetyMode.NONE) {
    Class.forName("androidx.compose.ui.node.LayoutNode")
}
private val layoutNodeLayoutDirection by lazy(LazyThreadSafetyMode.NONE) {
    layoutNode.getMethod("getLayoutDirection")
}
private val layoutNodeDensity by lazy(LazyThreadSafetyMode.NONE) {
    layoutNode.getMethod("getDensity")
}

/**
 * Replays this node's drawBehind draws into a recorder to see what they paint, since a Canvas keeps
 * its drawing in a lambda. Callers must treat a thrown error as not solid, which keeps the node masked.
 */
internal fun LayoutInfo.paintsSolidFill(): Boolean {
    val chain = nodeChain() ?: return false
    val nodes = generateSequence(chain.head.invoke(chain.nodes.invoke(this))) { chain.child.invoke(it) }.toList()
    val drawNodes = nodes.filter { it.draws() }
    // A delegate draws outside the chain, so the recorder can't see what it paints. Any other draw node
    // can reach the no-op drawContent and empty what the app reuses, such as a draw cache or a layer.
    if (nodes.any { delegatingNode.isInstance(it) } || drawNodes.any { !drawBehindNode.isInstance(it) }) return false
    // extra is a modifier's layer, whose effects the compositor applies where the recorder can't see them.
    // Coordinates of another size mean replay would run a lambda at a size the screen doesn't use.
    if (getModifierInfo().any { it.extra != null || it.coordinates.size != IntSize(width, height) }) return false
    val recorder = SolidFillRecorder(width.toFloat(), height.toFloat())
    val density = layoutNodeDensity.invoke(this) as Density
    val layoutDirection = layoutNodeLayoutDirection.invoke(this) as LayoutDirection
    CanvasDrawScope().draw(density, layoutDirection, recorder, Size(recorder.width, recorder.height)) {
        val scope = withoutContent()
        drawNodes.forEach { drawModifierNodeDraw.invoke(it, scope) }
    }
    return recorder.isSolidFill
}

// Every old-style modifier wraps in a DrawModifierNode, whose draw throws unless the modifier draws.
private fun Any.draws(): Boolean =
    drawModifierNode.isInstance(this) &&
        (!backwardsCompatNode.isInstance(this) || backwardsCompatNodeElement.invoke(this) is DrawModifier)

// A compiled implementation would miss DrawScope members added after our Compose compile floor, and
// their defaults can call themselves. R8 renames drawContent, so match it by its declaring interface.
private fun DrawScope.withoutContent(): ContentDrawScope =
    Proxy.newProxyInstance(
        ContentDrawScope::class.java.classLoader,
        arrayOf(ContentDrawScope::class.java),
    ) { _, method, args ->
        if (method.declaringClass == ContentDrawScope::class.java) {
            null
        } else {
            try {
                method.invoke(this, *args.orEmpty())
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    } as ContentDrawScope

// Solid means exactly one rectangle over the whole node with no shader, whatever its color, alpha or
// style. A shader can paint a bitmap through a rectangle, and several rectangles can draw a picture.
// Paint.shader doesn't see a shader set on the framework paint.
private class SolidFillRecorder(val width: Float, val height: Float) : Canvas {
    private var filled = false
    private var otherDrawing = false

    val isSolidFill: Boolean get() = filled && !otherDrawing

    override fun drawRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        paint: Paint,
    ) {
        if (
            !filled && left <= 0f && top <= 0f && right >= width && bottom >= height &&
            paint.shader == null && paint.asFrameworkPaint().shader == null
        ) {
            filled = true
        } else {
            otherDrawing = true
        }
    }

    private fun other() {
        otherDrawing = true
    }

    // CanvasDrawScope.draw wraps every draw in these.
    override fun save() = Unit

    override fun restore() = Unit

    override fun saveLayer(
        bounds: Rect,
        paint: Paint,
    ) = other()

    override fun translate(
        dx: Float,
        dy: Float,
    ) = other()

    override fun scale(
        sx: Float,
        sy: Float,
    ) = other()

    override fun rotate(degrees: Float) = other()

    override fun skew(
        sx: Float,
        sy: Float,
    ) = other()

    override fun concat(matrix: Matrix) = other()

    override fun clipRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        clipOp: ClipOp,
    ) = other()

    override fun clipPath(
        path: Path,
        clipOp: ClipOp,
    ) = other()

    override fun drawLine(
        p1: Offset,
        p2: Offset,
        paint: Paint,
    ) = other()

    override fun drawRoundRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radiusX: Float,
        radiusY: Float,
        paint: Paint,
    ) = other()

    override fun drawOval(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        paint: Paint,
    ) = other()

    override fun drawCircle(
        center: Offset,
        radius: Float,
        paint: Paint,
    ) = other()

    override fun drawArc(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        startAngle: Float,
        sweepAngle: Float,
        useCenter: Boolean,
        paint: Paint,
    ) = other()

    override fun drawPath(
        path: Path,
        paint: Paint,
    ) = other()

    override fun drawImage(
        image: ImageBitmap,
        topLeftOffset: Offset,
        paint: Paint,
    ) = other()

    override fun drawImageRect(
        image: ImageBitmap,
        srcOffset: IntOffset,
        srcSize: IntSize,
        dstOffset: IntOffset,
        dstSize: IntSize,
        paint: Paint,
    ) = other()

    override fun drawPoints(
        pointMode: PointMode,
        points: List<Offset>,
        paint: Paint,
    ) = other()

    override fun drawRawPoints(
        pointMode: PointMode,
        points: FloatArray,
        paint: Paint,
    ) = other()

    override fun drawVertices(
        vertices: Vertices,
        blendMode: BlendMode,
        paint: Paint,
    ) = other()

    override fun enableZ() = other()

    override fun disableZ() = other()
}
