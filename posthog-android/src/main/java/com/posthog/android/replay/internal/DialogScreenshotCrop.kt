package com.posthog.android.replay.internal

import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.ceil
import kotlin.math.floor

// Physical pixels in the decor's coordinate space. Keep this immutable: PixelCopy can mutate
// its source Rect, and the same geometry is used by draw verification and the scene cache.
internal data class DialogScreenshotCrop(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val opaque: Boolean,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun toRect(): Rect = Rect(left, top, right, bottom)
}

private const val BOTTOM_SHEET_BEHAVIOR = "com.google.android.material.bottomsheet.BottomSheetBehavior"
private const val MATERIAL_SHAPE = "com.google.android.material.shape.MaterialShapeDrawable"
private const val COORDINATOR = "androidx.coordinatorlayout.widget.CoordinatorLayout"

private val transparentContainers =
    setOf(
        "com.android.internal.policy.DecorView",
        "android.widget.FrameLayout",
        "android.widget.LinearLayout",
        "androidx.appcompat.widget.FitWindowsFrameLayout",
        "androidx.appcompat.widget.ContentFrameLayout",
        COORDINATOR,
    )

/**
 * Recognizes the stock Material dialog shell, not arbitrary custom dialog drawing. Only the
 * shell is visited: the sheet clips its children, so its subtree need not be walked for bounds.
 * Native shadows outside the content rectangle are omitted.
 */
@Suppress("DEPRECATION")
internal fun findDialogScreenshotCrop(decor: View): DialogScreenshotCrop? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || decor.width <= 0 || decor.height <= 0) return null
    return try {
        var remaining = 32
        var sheet: ViewGroup? = null

        fun visit(view: View): Boolean {
            if (view.visibility != View.VISIBLE || view.alpha <= 0f) return true
            if (--remaining < 0 || view.width <= 0 || view.height <= 0 || !view.hasCropCompatibleTransform()) return false
            if (view is FrameLayout && view.javaClass == FrameLayout::class.java && view.resourceEntryName() == "design_bottom_sheet") {
                if (sheet != null || (view.parent as? View)?.javaClass?.name != COORDINATOR || !view.clipChildren) return false
                val behavior = view.layoutParams.javaClass.getMethod("getBehavior").invoke(view.layoutParams)
                if (behavior?.javaClass?.name != BOTTOM_SHEET_BEHAVIOR) return false
                sheet = view
                return true
            }
            if (view.foreground != null || (view.background != null && view.background.opacity != PixelFormat.TRANSPARENT)) return false
            // Material's full-window outside-touch target draws nothing, but willNotDraw is false.
            if (view.javaClass == View::class.java && view.resourceEntryName() == "touch_outside") return true
            if (view !is ViewGroup || view.javaClass.name !in transparentContainers ||
                !view.willNotDraw() || !view.clipChildren
            ) {
                return false
            }
            for (index in 0 until view.childCount) {
                if (!visit(view.getChildAt(index))) return false
            }
            return true
        }
        if (!visit(decor)) return null
        val content = sheet ?: return null
        val bounds = RectF(0f, 0f, content.width.toFloat(), content.height.toFloat())
        var opaque = content.hasOpaqueCropBackground()
        var current: View = content
        while (true) {
            opaque = opaque && current.alpha == 1f &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || current.transitionAlpha == 1f) &&
                current.hasRectangularCropClip(bounds)
            current.clipBounds?.let { if (!bounds.intersect(RectF(it))) return null }
            if (current === decor) break
            val parent = current.parent as? ViewGroup ?: return null
            bounds.offset(current.left - parent.scrollX + current.translationX, current.top - parent.scrollY + current.translationY)
            if (!bounds.intersect(0f, 0f, parent.width.toFloat(), parent.height.toFloat())) return null
            if (parent.clipToPadding &&
                !bounds.intersect(
                    parent.paddingLeft.toFloat(),
                    parent.paddingTop.toFloat(),
                    (parent.width - parent.paddingRight).toFloat(),
                    (parent.height - parent.paddingBottom).toFloat(),
                )
            ) {
                return null
            }
            current = parent
        }
        val left = floor(bounds.left).toInt()
        val top = floor(bounds.top).toInt()
        val right = ceil(bounds.right).toInt()
        val bottom = ceil(bounds.bottom).toInt()
        // Fractional translation leaves antialiased edge pixels in the outward-rounded crop.
        opaque = opaque && bounds.left == left.toFloat() && bounds.top == top.toFloat() &&
            bounds.right == right.toFloat() && bounds.bottom == bottom.toFloat()
        DialogScreenshotCrop(left, top, right, bottom, opaque)
    } catch (_: Throwable) {
        // Optional library APIs and custom View implementations can vary independently of us.
        null
    }
}

private fun View.hasRectangularCropClip(bounds: RectF): Boolean {
    if (!clipToOutline) return true
    val outline = Outline()
    outlineProvider?.getOutline(this, outline)
    val rect = Rect()
    return outline.getRect(rect) && outline.radius == 0f && RectF(rect).contains(bounds)
}

private fun View.resourceEntryName(): String? = if (id == View.NO_ID) null else resources.getResourceEntryName(id)

private fun View.hasCropCompatibleTransform(): Boolean =
    scaleX == 1f && scaleY == 1f && rotation == 0f && rotationX == 0f && rotationY == 0f &&
        translationX.isFinite() && translationY.isFinite() &&
        (animation == null || animation?.hasEnded() == true)

@Suppress("DEPRECATION")
private fun View.hasOpaqueCropBackground(): Boolean {
    val drawable = background ?: return false
    if (foreground != null || drawable.alpha != 255 || drawable.colorFilter != null) return false
    if (drawable.opacity == PixelFormat.OPAQUE) return true
    if (drawable.javaClass.name != MATERIAL_SHAPE || drawable.bounds != Rect(0, 0, width, height)) return false
    return try {
        // MaterialShapeDrawable always reports TRANSLUCENT. Use its public fill/shape APIs
        // to recognize an opaque rectangle without reading back any window pixels.
        val type = drawable.javaClass
        val fill = type.getMethod("getFillColor").invoke(drawable) as? ColorStateList
        val tint = type.getMethod("getTintList").invoke(drawable) as? ColorStateList
        val style = type.getMethod("getPaintStyle").invoke(drawable) as? Paint.Style
        fill?.isOpaque == true && tint?.isOpaque != false && (style == Paint.Style.FILL || style == Paint.Style.FILL_AND_STROKE) &&
            drawable.transparentRegion?.isEmpty == true
    } catch (_: Throwable) {
        false
    }
}
