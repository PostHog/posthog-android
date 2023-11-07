package com.posthog.android.replay.internal

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent

internal interface PostHogRecorder {
    fun beginFrame(timestampMs: Long, width: Int, height: Int)
    fun save()
    fun restore()
    fun restoreToCount(currentSaveCount: Int, targetSaveCount: Int)
    fun translate(dx: Float, dy: Float)
    fun clipRectF(left: Float, top: Float, right: Float, bottom: Float)
    fun drawRoundRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        rx: Float,
        ry: Float,
        paint: Paint,
    )

    fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint)
    fun drawText(text: CharSequence, start: Int, end: Int, x: Float, y: Float, paint: Paint)
    fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint)
    fun concat(matrix: Matrix)
    fun scale(sx: Float, sy: Float)
    fun rotate(degrees: Float)
    fun skew(sx: Float, sy: Float)
    fun setMatrix(matrix: Matrix?)
    fun onTouchEvent(timestampMs: Long, event: MotionEvent)
    fun drawPath(path: Path, paint: Paint)
}
