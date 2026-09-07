package com.posthog.android.replay.internal

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [26])
internal class PixelCopyBitmapBufferTest {
    @Test
    fun `reuses and reconfigures one bitmap within a recording run`() {
        val buffer = PixelCopyBitmapBuffer()
        buffer.open()

        val first = buffer.acquire(51, 50, Bitmap.Config.RGB_565)!!
        assertEquals(Bitmap.Config.RGB_565, first.bitmap.config)
        first.release()

        val second = buffer.acquire(50, 51, Bitmap.Config.RGB_565)!!
        assertSame(first.bitmap, second.bitmap)
        assertEquals(50, second.bitmap.width)
        assertEquals(51, second.bitmap.height)
        second.release()

        buffer.close()
        assertTrue(first.bitmap.isRecycled)
    }

    @Test
    fun `changing color mode replaces the idle bitmap without disabling RGB565`() {
        val buffer = PixelCopyBitmapBuffer()
        buffer.open()
        val rgb = buffer.acquire(20, 20, Bitmap.Config.RGB_565)!!
        rgb.release()

        val argb = buffer.acquire(20, 20, Bitmap.Config.ARGB_8888)!!
        assertEquals(Bitmap.Config.ARGB_8888, argb.bitmap.config)
        assertTrue(rgb.bitmap.isRecycled)
        argb.release()

        val rgbAgain = buffer.acquire(20, 20, Bitmap.Config.RGB_565)!!
        assertEquals(Bitmap.Config.RGB_565, rgbAgain.bitmap.config)
        assertTrue(argb.bitmap.isRecycled)
        rgbAgain.release()
        buffer.close()
    }

    @Test
    fun `busy cached destination uses an uncached lease without recycling in flight pixels`() {
        val buffer = PixelCopyBitmapBuffer()
        buffer.open()
        val pending = buffer.acquire(20, 20, Bitmap.Config.ARGB_8888)!!
        val uncached = buffer.acquire(10, 10, Bitmap.Config.RGB_565)!!
        assertNotSame(pending.bitmap, uncached.bitmap)
        assertEquals(Bitmap.Config.RGB_565, uncached.bitmap.config)
        uncached.release()
        assertTrue(uncached.bitmap.isRecycled)
        assertFalse(pending.bitmap.isRecycled)

        pending.release()
        val reused = buffer.acquire(20, 20, Bitmap.Config.ARGB_8888)!!
        assertSame(pending.bitmap, reused.bitmap)
        reused.release()
        buffer.close()
    }

    @Test
    fun `late uncached completion cannot disturb a newer cached lease`() {
        val buffer = PixelCopyBitmapBuffer()
        buffer.open()
        val first = buffer.acquire(20, 20, Bitmap.Config.ARGB_8888)!!
        val uncached = buffer.acquire(20, 20, Bitmap.Config.ARGB_8888)!!
        first.release()
        val newer = buffer.acquire(20, 20, Bitmap.Config.ARGB_8888)!!
        uncached.release()
        first.release() // Releasing the same lease twice cannot release the newer owner.
        assertTrue(uncached.bitmap.isRecycled)
        assertFalse(newer.bitmap.isRecycled)
        val stillBusy = buffer.acquire(20, 20, Bitmap.Config.ARGB_8888)!!
        assertNotSame(newer.bitmap, stillBusy.bitmap)
        stillBusy.release()
        newer.release()
        buffer.close()
    }

    @Test
    fun `closed run detaches and recycles late cached and uncached leases`() {
        val buffer = PixelCopyBitmapBuffer()
        assertNull(buffer.acquire(50, 50, Bitmap.Config.ARGB_8888))
        buffer.open()
        val oldRun = buffer.acquire(50, 50, Bitmap.Config.ARGB_8888)!!
        val uncached = buffer.acquire(50, 50, Bitmap.Config.ARGB_8888)!!

        buffer.close()
        assertFalse(oldRun.bitmap.isRecycled)
        assertFalse(uncached.bitmap.isRecycled)
        buffer.open()
        val newRun = buffer.acquire(50, 50, Bitmap.Config.ARGB_8888)!!

        assertNotSame(oldRun.bitmap, newRun.bitmap)
        oldRun.release()
        uncached.release()
        assertTrue(oldRun.bitmap.isRecycled)
        assertTrue(uncached.bitmap.isRecycled)
        assertFalse(newRun.bitmap.isRecycled)

        newRun.release()
        buffer.close()
        assertTrue(newRun.bitmap.isRecycled)
    }

    @Test
    fun `falls back to ARGB8888 once and discards the RGB565 cache`() {
        val buffer = PixelCopyBitmapBuffer()
        buffer.open()
        val rgb565 = buffer.acquire(10, 10, Bitmap.Config.RGB_565)!!
        rgb565.release()

        assertTrue(buffer.fallbackToArgb8888())
        assertFalse(buffer.fallbackToArgb8888())
        assertTrue(rgb565.bitmap.isRecycled)

        val argb8888 = buffer.acquire(10, 10, Bitmap.Config.RGB_565)!!
        assertEquals(Bitmap.Config.ARGB_8888, argb8888.bitmap.config)
        argb8888.release()
        buffer.close()
    }

    @Test
    fun `RGB565 rejection leaves an ARGB cache usable and applies to busy acquisitions`() {
        val buffer = PixelCopyBitmapBuffer()
        buffer.open()
        val argb = buffer.acquire(10, 10, Bitmap.Config.ARGB_8888)!!
        argb.release()
        assertTrue(buffer.fallbackToArgb8888())
        assertFalse(argb.bitmap.isRecycled)

        val cached = buffer.acquire(10, 10, Bitmap.Config.RGB_565)!!
        assertSame(argb.bitmap, cached.bitmap)
        val uncached = buffer.acquire(10, 10, Bitmap.Config.RGB_565)!!
        assertEquals(Bitmap.Config.ARGB_8888, uncached.bitmap.config)
        uncached.release()
        cached.release()
        buffer.close()
    }
}
