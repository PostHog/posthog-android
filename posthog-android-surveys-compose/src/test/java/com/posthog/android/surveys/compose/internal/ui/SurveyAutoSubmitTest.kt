package com.posthog.android.surveys.compose.internal.ui

import com.posthog.surveys.PostHogDisplayChoiceQuestion
import com.posthog.surveys.PostHogDisplaySurveyTextContentType
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals

@RunWith(Parameterized::class)
internal class SurveyAutoSubmitTest(
    private val enabled: Boolean,
    private val multiple: Boolean,
    private val openChoice: Boolean,
    private val expected: Boolean,
) {
    @Test
    fun `only single choice without an open option auto-submits`() {
        val question =
            PostHogDisplayChoiceQuestion(
                id = "question", question = "Choose", questionDescription = null,
                questionDescriptionContentType = PostHogDisplaySurveyTextContentType.TEXT,
                isOptional = false, buttonText = null, choices = listOf("First", "Other"),
                hasOpenChoice = openChoice, shuffleOptions = false, isMultipleChoice = multiple,
                skipSubmitButton = enabled,
            )
        assertEquals(expected, question.shouldAutoSubmit)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "enabled={0}, multiple={1}, open={2}")
        fun cases(): List<Array<Boolean>> =
            listOf(
                arrayOf(true, false, false, true),
                arrayOf(true, false, true, false),
                arrayOf(true, true, false, false),
                arrayOf(true, true, true, false),
                arrayOf(false, false, false, false),
                arrayOf(false, false, true, false),
                arrayOf(false, true, false, false),
                arrayOf(false, true, true, false),
            )
    }
}
