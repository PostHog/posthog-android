package com.posthog.android.replay

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

// DrawModifierNode is newer than our Compose compile floor, so look it up by name.
private val drawModifierNode by lazy(LazyThreadSafetyMode.NONE) {
    Class.forName("androidx.compose.ui.node.DrawModifierNode")
}
private val drawModifierNodeDraw by lazy(LazyThreadSafetyMode.NONE) {
    drawModifierNode.getMethod("draw", ContentDrawScope::class.java)
}

/**
 * Replays this node's draw modifiers into a recorder to see what they paint, since a Canvas keeps
 * its drawing in a lambda. Callers must treat a thrown error as not solid, which keeps the node masked.
 * That includes a StackOverflowError: newer Compose adds a drawImage overload whose default calls
 * itself, and this scope compiles against a Compose without it, so it can't override it.
 */
internal fun LayoutInfo.paintsSolidFill(density: Density): Boolean {
    val chain = nodeChain() ?: return false
    val nodes = generateSequence(chain.head.invoke(chain.nodes.invoke(this))) { chain.child.invoke(it) }.toList()
    val recorder = SolidFillRecorder(width.toFloat(), height.toFloat())
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, recorder, Size(recorder.width, recorder.height)) {
        val scope =
            object : ContentDrawScope, DrawScope by this {
                override fun drawContent() = Unit
            }
        nodes.filter { drawModifierNode.isInstance(it) }.forEach { drawModifierNodeDraw.invoke(it, scope) }
    }
    return recorder.isSolidFill
}

// Solid means exactly one plain rectangle over the whole node. A shader can paint a bitmap through a
// rectangle, and several rectangles can draw a picture.
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
        if (!filled && left <= 0f && top <= 0f && right >= width && bottom >= height && paint.shader == null) {
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
