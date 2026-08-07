package com.posthog.android.surveys.compose.internal.theme

import com.posthog.surveys.PostHogDisplaySurveyAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SurveyAppearanceResolveTest {
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
}
