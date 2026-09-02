package com.posthog.android.replay.internal

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the reusable destination bitmap used by session replay PixelCopy requests.
 *
 * Only one lease can be active per recording run. Closing a run recycles an idle bitmap and
 * detaches an in-flight lease so a late callback cannot return it to a later run.
 */
internal class PixelCopyBitmapBuffer {
    private var isOpen = false
    private var generation = 0L
    private var nextLeaseId = 0L
    private var activeLeaseId: Long? = null
    private var idleBitmap: Bitmap? = null
    private var bitmapConfig = Bitmap.Config.RGB_565

    @Synchronized
    fun open() {
        if (!isOpen) {
            generation++
            isOpen = true
        }
    }

    @Synchronized
    fun acquire(
        width: Int,
        height: Int,
    ): Lease? {
        if (!isOpen || activeLeaseId != null) {
            return null
        }

        val bitmap = obtainBitmap(width, height)
        val leaseId = ++nextLeaseId
        activeLeaseId = leaseId
        return Lease(this, bitmap, generation, leaseId)
    }

    @Synchronized
    fun fallbackToArgb8888(): Boolean {
        if (bitmapConfig == Bitmap.Config.ARGB_8888) {
            return false
        }
        bitmapConfig = Bitmap.Config.ARGB_8888
        idleBitmap?.recycle()
        idleBitmap = null
        return true
    }

    @Synchronized
    fun close() {
        if (!isOpen && idleBitmap == null && activeLeaseId == null) {
            return
        }
        isOpen = false
        activeLeaseId = null
        idleBitmap?.recycle()
        idleBitmap = null
    }

    @Synchronized
    private fun release(lease: Lease) {
        val ownsActiveLease = lease.generation == generation && lease.id == activeLeaseId
        if (ownsActiveLease) {
            activeLeaseId = null
        }
        if (isOpen && ownsActiveLease && lease.bitmap.config == bitmapConfig) {
            idleBitmap = lease.bitmap
        } else {
            lease.bitmap.recycle()
        }
    }

    private fun obtainBitmap(
        width: Int,
        height: Int,
    ): Bitmap {
        require(width > 0 && height > 0) { "PixelCopy bitmap dimensions must be positive" }

        val bitmap = idleBitmap
        idleBitmap = null
        if (bitmap == null || bitmap.isRecycled || bitmap.config != bitmapConfig) {
            bitmap?.recycle()
            return Bitmap.createBitmap(width, height, bitmapConfig)
        }
        if (bitmap.width == width && bitmap.height == height) {
            return bitmap
        }

        return try {
            bitmap.reconfigure(width, height, bitmapConfig)
            bitmap
        } catch (_: IllegalArgumentException) {
            bitmap.recycle()
            Bitmap.createBitmap(width, height, bitmapConfig)
        }
    }

    internal class Lease internal constructor(
        private val owner: PixelCopyBitmapBuffer,
        val bitmap: Bitmap,
        internal val generation: Long,
        internal val id: Long,
    ) {
        private val released = AtomicBoolean(false)

        fun release() {
            if (released.compareAndSet(false, true)) {
                owner.release(this)
            }
        }
    }
}
