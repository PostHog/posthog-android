package com.posthog.android.replay.internal

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [26])
internal class PixelCopyBitmapBufferTest {
    @Test
    fun `reuses and reconfigures one RGB565 bitmap within a recording run`() {
        val buffer = PixelCopyBitmapBuffer()
        buffer.open()

        val first = buffer.acquire(51, 50)!!
        assertEquals(Bitmap.Config.RGB_565, first.bitmap.config)
        first.release()

        val second = buffer.acquire(50, 51)!!
        assertSame(first.bitmap, second.bitmap)
        assertEquals(50, second.bitmap.width)
        assertEquals(51, second.bitmap.height)
        second.release()

        buffer.close()
        assertTrue(first.bitmap.isRecycled)
    }

    @Test
    fun `closed run detaches and recycles a late lease`() {
        val buffer = PixelCopyBitmapBuffer()
        buffer.open()
        val oldRun = buffer.acquire(50, 50)!!

        buffer.close()
        buffer.open()
        val newRun = buffer.acquire(50, 50)!!

        assertNotSame(oldRun.bitmap, newRun.bitmap)
        oldRun.release()
        assertTrue(oldRun.bitmap.isRecycled)
        assertFalse(newRun.bitmap.isRecycled)

        newRun.release()
        buffer.close()
        assertTrue(newRun.bitmap.isRecycled)
    }

    @Test
    fun `falls back to ARGB8888 once and discards the RGB565 cache`() {
        val buffer = PixelCopyBitmapBuffer()
        buffer.open()
        val rgb565 = buffer.acquire(10, 10)!!
        rgb565.release()

        assertTrue(buffer.fallbackToArgb8888())
        assertFalse(buffer.fallbackToArgb8888())
        assertTrue(rgb565.bitmap.isRecycled)

        val argb8888 = buffer.acquire(10, 10)!!
        assertEquals(Bitmap.Config.ARGB_8888, argb8888.bitmap.config)
        argb8888.release()
        buffer.close()
    }
}
