package com.posthog.android.replay.internal

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class ScreenshotScalingTest {
    @Test
    fun `null scale resolves to one pixel per dp`() {
        assertEquals(1f / 3f, resolveScreenshotScale(null, 3f))
        assertEquals(0.5f, resolveScreenshotScale(null, 2f))
    }

    @Test
    fun `null scale captures full resolution at density of one or less`() {
        assertEquals(1f, resolveScreenshotScale(null, 1f))
        assertEquals(1f, resolveScreenshotScale(null, 0.75f))
    }

    @Test
    fun `null scale is clamped for very high densities`() {
        assertEquals(0.1f, resolveScreenshotScale(null, 20f))
    }

    @Test
    fun `invalid density resolves to full resolution`() {
        assertEquals(1f, resolveScreenshotScale(null, 0f))
        assertEquals(1f, resolveScreenshotScale(null, -2f))
        assertEquals(1f, resolveScreenshotScale(null, Float.NaN))
        assertEquals(1f, resolveScreenshotScale(null, Float.POSITIVE_INFINITY))
    }

    @Test
    fun `explicit scale ignores density`() {
        assertEquals(0.5f, resolveScreenshotScale(0.5f, 3f))
        assertEquals(1f, resolveScreenshotScale(1f, 3f))
    }

    @Test
    fun `dimensions round up to at least one pixel`() {
        assertEquals(101, scaledScreenshotDimension(301, 1f / 3f))
        assertEquals(1, scaledScreenshotDimension(1, 0.1f))
    }

    @Test
    fun `float error does not add a pixel to an exact dp size`() {
        val scale = resolveScreenshotScale(null, 3.5f)
        assertEquals(100, scaledScreenshotDimension(350, scale))
        assertEquals(400, scaledScreenshotDimension(1400, scale))
        assertEquals(101, scaledScreenshotDimension(351, scale))
    }

    @Test
    fun `copy goes straight to the target up to a 2 to 1 ratio`() {
        assertNull(twoStageCopySize(200, 100, 100, 50))
        assertNull(twoStageCopySize(100, 100, 100, 100))
    }

    @Test
    fun `copy uses a 2x intermediate above a 2 to 1 ratio on either axis`() {
        assertEquals(200 to 200, twoStageCopySize(300, 300, 100, 100))
        assertEquals(200 to 100, twoStageCopySize(300, 100, 100, 100))
        assertEquals(100 to 200, twoStageCopySize(100, 300, 100, 100))
    }

    @Test
    fun `filtered downscale keeps one pixel lines at every phase`() {
        for (phase in 0 until 6) {
            val source = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888)
            source.eraseColor(Color.WHITE)
            for (y in 0 until 60) {
                for (x in phase until 60 step 6) {
                    source.setPixel(x, y, Color.BLACK)
                }
            }

            val target = source.downscaleFiltered(30, 30)
            try {
                assertEquals(30, target.width)
                assertEquals(30, target.height)
                val darkest = (0 until 30).minOf { Color.red(target.getPixel(it, 15)) }
                assertTrue(darkest < 200, "line at phase $phase vanished, darkest=$darkest")
            } finally {
                source.recycle()
                target.recycle()
            }
        }
    }
}
