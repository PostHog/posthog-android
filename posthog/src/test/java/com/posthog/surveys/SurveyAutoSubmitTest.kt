package com.posthog.surveys

import com.posthog.PostHogConfig
import com.posthog.internal.PostHogSerializer
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(Parameterized::class)
internal class SurveyAutoSubmitTest(
    private val type: String,
    private val enabled: Boolean?,
    private val display: String,
) {
    @Test
    fun `auto-submit setting survives decoding and display mapping`() {
        val serializer = PostHogSerializer(PostHogConfig("test-key"))
        val json =
            mapOf(
                "id" to "question",
                "type" to type,
                "question" to "Choose",
                "choices" to listOf("First", "Second"),
                "display" to display,
                "scale" to 5,
                "skipSubmitButton" to enabled,
            )
        val question = assertNotNull(serializer.deserializeList<SurveyQuestion>(listOf(json))?.firstOrNull())
        assertEquals(enabled, question.skipSubmitButton)
        val rendered = assertNotNull(PostHogDisplaySurveyQuestion.fromSurveyQuestion(question))
        val skipSubmitButton =
            when (rendered) {
                is PostHogDisplayRatingQuestion -> rendered.skipSubmitButton
                is PostHogDisplayChoiceQuestion -> rendered.skipSubmitButton
                else -> error("Unexpected question type")
            }
        assertEquals(enabled == true, skipSubmitButton)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}, enabled={1}, display={2}")
        fun cases(): List<Array<Any?>> =
            listOf("rating", "single_choice", "multiple_choice").flatMap { type ->
                listOf(true, false, null).flatMap { enabled ->
                    (if (type == "rating") listOf("number", "emoji") else listOf("number")).map { display ->
                        arrayOf(type, enabled, display)
                    }
                }
            }
    }
}
