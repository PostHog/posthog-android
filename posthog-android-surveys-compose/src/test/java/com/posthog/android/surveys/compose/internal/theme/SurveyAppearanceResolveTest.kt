package com.posthog.android.surveys.compose.internal.theme

import com.posthog.surveys.PostHogDisplaySurveyAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertNull

internal class SurveyAppearanceResolveTest {
    @Test
    fun `open text placeholder is empty unless configured`() {
        listOf(null, "", "  ").forEach { placeholder ->
            assertEquals("", PostHogDisplaySurveyAppearance(placeholder = placeholder).resolve().placeholder)
        }

        assertEquals("Tell us more", PostHogDisplaySurveyAppearance(placeholder = "Tell us more").resolve().placeholder)
    }

    @Test
    fun `blank intro copy resolves to null so the sheet can skip an empty intro`() {
        val resolved =
            PostHogDisplaySurveyAppearance(
                displayIntroScreen = true,
                introScreenHeader = "  ",
                introScreenDescription = "",
            ).resolve()

        assertTrue(resolved.displayIntroScreen)
        assertNull(resolved.introScreenHeader)
        assertNull(resolved.introScreenDescription)
        assertEquals("Get started", resolved.introScreenButtonText)
    }

    @Test
    fun `configured intro copy survives resolution`() {
        val resolved =
            PostHogDisplaySurveyAppearance(
                displayIntroScreen = true,
                introScreenHeader = "Welcome!",
                introScreenDescription = "Two quick questions.",
                introScreenButtonText = "Let's go",
            ).resolve()

        assertEquals("Welcome!", resolved.introScreenHeader)
        assertEquals("Two quick questions.", resolved.introScreenDescription)
        assertEquals("Let's go", resolved.introScreenButtonText)
    }

    @Test
    fun `font family keeps the host font unless a specific family is configured`() {
        listOf(null, "", "  ", "inherit", "system-ui", "\"\"").forEach { font ->
            assertNull(surveyFontFamilyName(font), "font=$font")
        }
    }

    @Test
    fun `font family uses the lowercased first css family`() {
        assertEquals("times new roman", surveyFontFamilyName("Times New Roman"))
        assertEquals("courier new", surveyFontFamilyName(" 'Courier New' , monospace"))
        assertEquals("arial", surveyFontFamilyName("\"Arial\", sans-serif"))
    }
}
