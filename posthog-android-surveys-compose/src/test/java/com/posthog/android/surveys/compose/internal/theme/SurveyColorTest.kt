package com.posthog.android.surveys.compose.internal.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test

internal class SurveyColorTest {
    private fun argb(input: String?): Int = parseSurveyColor(input).toArgb()

    @Test
    fun `named colors resolve case-insensitively`() {
        assertEquals(0xFFFF0000.toInt(), argb("red"))
        assertEquals(0xFFFF0000.toInt(), argb("RED"))
        assertEquals(0xFFFFFFFF.toInt(), argb("white"))
        assertEquals(0xFF000000.toInt(), argb("black"))
    }

    @Test
    fun `transparent aliases resolve to fully transparent`() {
        assertEquals(0x00000000, argb("transparent"))
        assertEquals(0x00000000, argb("clear"))
    }

    @Test
    fun `hex with and without leading hash resolve the same`() {
        assertEquals(0xFFFF0000.toInt(), argb("#FF0000"))
        assertEquals(0xFFFF0000.toInt(), argb("FF0000"))
        assertEquals(0xFF0000FF.toInt(), argb("#0000FF"))
    }

    @Test
    fun `short hex forms are expanded`() {
        // #RGB -> #RRGGBB (opaque)
        assertEquals(0xFFFF0000.toInt(), argb("#F00"))
        // #RGBA -> #RRGGBBAA
        assertEquals(0x88FF0000.toInt(), argb("#F008"))
    }

    @Test
    fun `eight-digit hex carries its alpha channel`() {
        assertEquals(0xFFFF0000.toInt(), argb("#FF0000FF"))
        assertEquals(0x80FF0000.toInt(), argb("#FF000080"))
    }

    @Test
    fun `null blank and unrecognized input fall back to transparent`() {
        assertEquals(0x00000000, argb(null))
        assertEquals(0x00000000, argb(""))
        assertEquals(0x00000000, argb("   "))
        assertEquals(0x00000000, argb("#GGG"))
        assertEquals(0x00000000, argb("notacolor"))
    }

    @Test
    fun `contrasting text color follows luminance`() {
        assertEquals(Color.Black, Color.White.contrastingTextColor())
        assertEquals(Color.White, Color.Black.contrastingTextColor())
    }
}
