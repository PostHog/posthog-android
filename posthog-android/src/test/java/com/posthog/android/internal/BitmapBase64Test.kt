package com.posthog.android.internal

import android.graphics.Bitmap
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
internal class BitmapBase64Test {
    private fun bitmap(
        width: Int = 8,
        height: Int = 8,
    ): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    private fun expectedDataUri(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        quality: Int,
        htmlFormat: String,
    ): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(format, quality, out)
        val encoded = Base64.encodeToString(out.toByteArray(), Base64.DEFAULT)
        return "data:image/$htmlFormat;base64,$encoded"
    }

    // The streaming encoder does not always close with a trailing newline, so both sides are
    // trimmed. The wrapped newlines inside the payload still have to match.
    private fun assertEncodesLike(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        htmlFormat: String,
    ) {
        assertEquals(
            expectedDataUri(bitmap, format, 30, htmlFormat).trimEnd('\n'),
            bitmap.base64(format, 30)?.trimEnd('\n'),
        )
    }

    @Test
    fun `encodes the same data uri as encodeToString`() {
        assertEncodesLike(bitmap(), Bitmap.CompressFormat.PNG, "png")
    }

    @Test
    fun `encodes the same data uri for a wrapped payload`() {
        val bitmap = bitmap(width = 200, height = 200)

        assertTrue(bitmap.base64(Bitmap.CompressFormat.PNG, 30)?.trimEnd('\n')?.contains('\n') == true)
        assertEncodesLike(bitmap, Bitmap.CompressFormat.PNG, "png")
    }

    @Test
    fun `payload decodes back to the compressed bytes`() {
        val bitmap = bitmap()
        val compressed = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 30, compressed)

        val payload = bitmap.base64(Bitmap.CompressFormat.PNG, 30)?.substringAfter("base64,")

        assertContentEquals(compressed.toByteArray(), Base64.decode(payload, Base64.DEFAULT))
    }

    @Test
    fun `webp base64 uses the webp media type`() {
        val encoded = bitmap().webpBase64()

        assertTrue(encoded?.startsWith("data:image/webp;base64,") == true)
    }

    @Test
    fun `returns null for a recycled bitmap`() {
        val bitmap = bitmap()
        bitmap.recycle()

        assertNull(bitmap.base64())
        assertNull(bitmap.webpBase64())
    }
}
