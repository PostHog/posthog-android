package com.posthog.android.surveys.compose.internal.ui

import android.graphics.Typeface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.posthog.android.surveys.compose.internal.theme.resolve
import com.posthog.surveys.PostHogDisplayOpenQuestion
import com.posthog.surveys.PostHogDisplaySurvey
import com.posthog.surveys.PostHogDisplaySurveyAppearance
import com.posthog.surveys.PostHogDisplaySurveyTextContentType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class SurveyFontFamilyInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `configured font family reaches every survey text`() {
        val fontFamily = PostHogDisplaySurveyAppearance(fontFamily = FONT).resolve().fontFamily
        compose.setContent {
            MaterialTheme {
                SurveySheet(
                    survey =
                        PostHogDisplaySurvey(
                            id = "font",
                            name = "Font",
                            questions =
                                listOf(
                                    PostHogDisplayOpenQuestion(
                                        "q",
                                        "Question",
                                        "Description",
                                        PostHogDisplaySurveyTextContentType.TEXT,
                                        false,
                                        "Send",
                                    ),
                                ),
                            appearance = PostHogDisplaySurveyAppearance(fontFamily = FONT, placeholder = "Placeholder"),
                        ),
                    onSurveyShown = {},
                    onSubmit = { _, _ -> null },
                    onClose = {},
                )
            }
        }

        listOf("Question", "Description", "Placeholder", "Send").forEach { text ->
            assertEquals(fontFamily, fontFamilyOf(text), "text=$text")
        }
    }

    @Test
    fun `configured font family keeps medium weight for button labels`() {
        val fontFamily = PostHogDisplaySurveyAppearance(fontFamily = FONT).resolve().fontFamily!!
        lateinit var survey: Typeface
        lateinit var system: Typeface
        compose.setContent {
            val resolver = LocalFontFamilyResolver.current
            survey = resolver.resolve(fontFamily, FontWeight.Medium).value as Typeface
            system = resolver.resolve(FontFamily.SansSerif, FontWeight.Medium).value as Typeface
        }

        compose.runOnIdle { assertEquals(system.weight, survey.weight) }
    }

    private fun fontFamilyOf(text: String): FontFamily? {
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        return layouts.single().layoutInput.style.fontFamily
    }

    private companion object {
        const val FONT = "sans-serif"
    }
}
