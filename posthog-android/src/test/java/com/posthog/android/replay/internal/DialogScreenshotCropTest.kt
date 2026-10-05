package com.posthog.android.replay.internal

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28, 35], qualifiers = "w600dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class DialogScreenshotCropTest {
    @Test
    fun `stock material shell crops to its opaque content`() {
        MaterialDialogFixture().use { h ->
            assertEquals(DialogScreenshotCrop(50, 600, 550, 900, true), findDialogScreenshotCrop(h.root))
        }
    }

    @Test
    fun `source rectangles are independent of immutable capture geometry`() {
        val crop = DialogScreenshotCrop(50, 600, 550, 900, true)
        crop.toRect().offset(20, 30)
        assertEquals(Rect(50, 600, 550, 900), crop.toRect())
    }

    @Test
    fun `translation and clipping stay in decor coordinates with outward rounding`() {
        MaterialDialogFixture().use { h ->
            h.sheet.translationX = -70.5f
            h.sheet.translationY = 0.5f
            assertEquals(DialogScreenshotCrop(0, 600, 480, 901, false), findDialogScreenshotCrop(h.root))
            h.sheet.translationX = 0f
            h.sheet.translationY = 150f
            assertEquals(DialogScreenshotCrop(50, 750, 550, 1000, true), findDialogScreenshotCrop(h.root))
        }
    }

    @Test
    fun `translucent backgrounds and ancestor alpha keep alpha`() {
        MaterialDialogFixture().use { h ->
            h.sheet.background = ColorDrawable(0x80ffffff.toInt())
            assertFalse(assertNotNull(findDialogScreenshotCrop(h.root)).opaque)
            h.sheet.background = ColorDrawable(Color.WHITE)
            (h.sheet.parent as View).alpha = 0.5f
            assertFalse(assertNotNull(findDialogScreenshotCrop(h.root)).opaque)
        }
    }

    @Test
    fun `opaque material rectangles use RGB but rounded and translucent shapes keep alpha`() {
        MaterialDialogFixture().use { h ->
            val shape =
                MaterialShapeDrawable().apply {
                    fillColor = ColorStateList.valueOf(Color.WHITE)
                    setBounds(0, 0, 500, 300)
                }
            h.sheet.background = shape
            assertTrue(assertNotNull(findDialogScreenshotCrop(h.root)).opaque)
            shape.shapeAppearanceModel = ShapeAppearanceModel.builder().setAllCornerSizes(30f).build()
            assertFalse(assertNotNull(findDialogScreenshotCrop(h.root)).opaque)
            shape.interpolation = 0f
            assertTrue(assertNotNull(findDialogScreenshotCrop(h.root)).opaque)
            shape.fillColor = ColorStateList.valueOf(0x80ffffff.toInt())
            assertFalse(assertNotNull(findDialogScreenshotCrop(h.root)).opaque)
        }
    }

    @Test
    fun `drawing outside the recognized sheet falls back to the window`() {
        MaterialDialogFixture().use { h ->
            val outside = View(h.activity).apply { setBackgroundColor(Color.RED) }
            (h.sheet.parent as ViewGroup).addView(outside)
            outside.layout(0, 0, 100, 100)
            assertNull(findDialogScreenshotCrop(h.root))
        }
    }

    @Test
    fun `backgrounds on the dialog shell fall back to the window`() {
        MaterialDialogFixture().use { h ->
            (h.sheet.parent as View).setBackgroundColor(0x40000000)
            assertNull(findDialogScreenshotCrop(h.root))
        }
    }

    @Test
    fun `unsupported transforms and unclipped content fall back to the window`() {
        MaterialDialogFixture().use { h ->
            h.sheet.rotation = 10f
            assertNull(findDialogScreenshotCrop(h.root))
            h.sheet.rotation = 0f
            h.sheet.clipChildren = false
            assertNull(findDialogScreenshotCrop(h.root))
        }
    }

    @Test
    @Config(sdk = [26, 27])
    fun `legacy platforms retain full window capture`() {
        MaterialDialogFixture().use { h -> assertNull(findDialogScreenshotCrop(h.root)) }
    }
}
