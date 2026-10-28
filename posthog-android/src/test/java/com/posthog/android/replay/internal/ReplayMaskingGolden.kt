package com.posthog.android.replay.internal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.posthog.android.replay.PostHogReplayIntegration
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

internal fun assertReplayMaskingGolden(
    name: String,
    description: String,
    original: Bitmap,
    masked: Bitmap,
    originalLabel: String = "Original",
) {
    assertEquals(original.width, masked.width)
    assertEquals(original.height, masked.height)
    val actual = Bitmap.createBitmap(original.width * 2 + 24, original.height + 64, Bitmap.Config.ARGB_8888)
    try {
        actual.eraseColor(Color.WHITE)
        val canvas = Canvas(actual)
        val text =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 13f
                typeface = Typeface.MONOSPACE
            }
        canvas.drawText(description, 8f, 18f, text)
        canvas.drawText(originalLabel, 8f, 44f, text)
        canvas.drawText("SDK masked", original.width + 16f, 44f, text)
        canvas.drawBitmap(original, 8f, 56f, null)
        canvas.drawBitmap(masked, original.width + 16f, 56f, null)
        val resource = "snapshots/replay-masking/$name.png"
        if (System.getenv("UPDATE_MASKING_SNAPSHOTS") == "1") {
            writePng(File("src/test/resources/$resource"), actual)
            return
        }
        val failures = File("build/reports/replay-masking-snapshots/$name")
        val expectedStream = PostHogReplayIntegration::class.java.classLoader!!.getResourceAsStream(resource)
        if (expectedStream == null) {
            writePng(File(failures, "actual.png"), actual)
            fail("Missing $resource. Actual: ${failures.absolutePath}. Run make recordMaskingSnapshots and review the images.")
        }
        val expected = expectedStream.use { assertNotNull(BitmapFactory.decodeStream(it), "Cannot decode $resource") }
        try {
            if (expected.sameAs(actual)) return
            writePng(File(failures, "expected.png"), expected)
            writePng(File(failures, "actual.png"), actual)
            val diff = Bitmap.createBitmap(actual.width, actual.height, Bitmap.Config.ARGB_8888)
            try {
                for (y in 0 until actual.height) {
                    for (x in 0 until actual.width) {
                        val matches = x < expected.width && y < expected.height && expected.getPixel(x, y) == actual.getPixel(x, y)
                        diff.setPixel(x, y, if (matches) Color.WHITE else Color.MAGENTA)
                    }
                }
                writePng(File(failures, "diff.png"), diff)
            } finally {
                diff.recycle()
            }
            fail("Masking snapshot $name changed. Expected, actual and diff: ${failures.absolutePath}")
        } finally {
            expected.recycle()
        }
    } finally {
        actual.recycle()
    }
}

private fun writePng(
    file: File,
    bitmap: Bitmap,
) {
    file.parentFile?.mkdirs()
    file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
}
