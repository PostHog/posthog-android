package com.posthog.android.surveys.compose.internal.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.posthog.surveys.PostHogDisplayChoiceQuestion
import com.posthog.surveys.PostHogDisplayRatingQuestion
import com.posthog.surveys.PostHogDisplaySurvey
import com.posthog.surveys.PostHogDisplaySurveyAppearance
import com.posthog.surveys.PostHogDisplaySurveyQuestion
import com.posthog.surveys.PostHogDisplaySurveyRatingType
import com.posthog.surveys.PostHogDisplaySurveyTextContentType
import com.posthog.surveys.PostHogNextSurveyQuestion
import com.posthog.surveys.PostHogSurveyResponse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [28])
internal class SurveyAutoSubmitInteractionTest(
    private val kind: String,
    private val enabled: Boolean,
) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `selection submits once and follows branching without carrying selection to the next question`() {
        val responses = mutableListOf<Pair<Int, PostHogSurveyResponse>>()
        var closes = 0
        val survey =
            PostHogDisplaySurvey(
                id = "interaction-survey",
                name = "Interaction survey",
                questions =
                    listOf(
                        question("First question", enabled),
                        question("Skipped question", false),
                        question("Last question", false),
                    ),
                appearance = PostHogDisplaySurveyAppearance(displayThankYouMessage = false),
            )
        compose.setContent {
            MaterialTheme {
                SurveySheet(
                    survey = survey,
                    onSurveyShown = {},
                    onSubmit = { index, response ->
                        responses.add(index to response)
                        PostHogNextSurveyQuestion(2, index == 2)
                    },
                    onClose = { closes++ },
                )
            }
        }
        compose.onNodeWithText("First question").assertIsDisplayed()
        val autoSubmit = enabled && kind in listOf("number", "emoji", "single")
        if (autoSubmit) {
            compose.onNodeWithText("Submit").assertDoesNotExist()
        } else {
            compose.onNodeWithText("Submit").assertIsNotEnabled()
        }

        selectAnswer()
        if (!autoSubmit) {
            compose.runOnIdle { assertEquals(emptyList(), responses) }
            compose.onNodeWithText("Submit").assertIsEnabled().performClick()
        }

        compose.onNodeWithText("Last question").assertIsDisplayed()
        compose.onNodeWithText("Skipped question").assertDoesNotExist()
        compose.onNodeWithText("Submit").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf(0 to expectedResponse()), responses)
            assertEquals(0, closes)
        }

        // Selecting the same value again must select it on the new question, not deselect stale state.
        selectAnswer()
        compose.onNodeWithText("Submit").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(0 to expectedResponse(), 2 to expectedResponse()), responses)
            assertEquals(1, closes)
        }
    }

    private fun selectAnswer() {
        when (kind) {
            "number" -> compose.onNodeWithText("3").performClick()
            "emoji" -> {
                // Emoji choices are canvases without text; other sheet actions have accessible labels.
                val choices =
                    compose.onAllNodes(
                        hasClickAction() and
                            SemanticsMatcher.keyNotDefined(SemanticsProperties.Text) and
                            SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription),
                    )
                choices.assertCountEquals(5)
                choices[2].performClick()
            }
            "open" -> {
                compose.onNodeWithText("Other:").performClick()
                compose.onNode(hasSetTextAction()).performTextInput("Free form")
            }
            else -> compose.onNodeWithText("First").performClick()
        }
    }

    private fun expectedResponse(): PostHogSurveyResponse =
        when (kind) {
            "number", "emoji" -> PostHogSurveyResponse.Rating(3)
            "multiple" -> PostHogSurveyResponse.MultipleChoice(listOf("First"))
            "open" -> PostHogSurveyResponse.SingleChoice("Free form")
            else -> PostHogSurveyResponse.SingleChoice("First")
        }

    private fun question(
        title: String,
        autoSubmit: Boolean,
    ): PostHogDisplaySurveyQuestion =
        when (kind) {
            "number", "emoji" ->
                PostHogDisplayRatingQuestion(
                    id = title, question = title, questionDescription = null,
                    questionDescriptionContentType = PostHogDisplaySurveyTextContentType.TEXT,
                    isOptional = false, buttonText = "Submit",
                    ratingType = if (kind == "number") PostHogDisplaySurveyRatingType.NUMBER else PostHogDisplaySurveyRatingType.EMOJI,
                    scaleLowerBound = 1, scaleUpperBound = 5, lowerBoundLabel = "", upperBoundLabel = "",
                    skipSubmitButton = autoSubmit,
                )
            else ->
                PostHogDisplayChoiceQuestion(
                    id = title, question = title, questionDescription = null,
                    questionDescriptionContentType = PostHogDisplaySurveyTextContentType.TEXT,
                    isOptional = false, buttonText = "Submit", choices = listOf("First", "Other"),
                    hasOpenChoice = kind == "open", shuffleOptions = false, isMultipleChoice = kind == "multiple",
                    skipSubmitButton = autoSubmit,
                )
        }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "kind={0}, enabled={1}")
        fun cases(): List<Array<Any>> =
            listOf("number", "emoji", "single", "open", "multiple").flatMap { kind ->
                listOf(true, false).map { enabled -> arrayOf(kind, enabled) }
            }
    }
}
