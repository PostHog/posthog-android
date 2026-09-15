package com.posthog.android.surveys.compose.internal.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.posthog.surveys.PostHogDisplayChoiceQuestion
import com.posthog.surveys.PostHogDisplaySurvey
import com.posthog.surveys.PostHogDisplaySurveyAppearance
import com.posthog.surveys.PostHogDisplaySurveyTextContentType
import com.posthog.surveys.PostHogNextSurveyQuestion
import com.posthog.surveys.PostHogSurveyResponse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [28])
internal class SurveyShuffleInteractionTest(
    private val isMultipleChoice: Boolean,
    private val hasOpenChoice: Boolean,
    private val shouldShuffleOptions: Boolean,
) {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `display order stays stable through selection and submits the displayed answer`() {
        val choices = listOf("A", "B", "C") + if (hasOpenChoice) listOf("Other") else emptyList()
        val labels = choices.map { if (hasOpenChoice && it == "Other") "Other:" else it }
        val responses = mutableListOf<PostHogSurveyResponse>()
        val survey =
            PostHogDisplaySurvey(
                id = "shouldShuffleOptions",
                name = "Shuffle",
                questions = listOf(question("First", choices), question("Last", listOf("Done"))),
                appearance = PostHogDisplaySurveyAppearance(displayThankYouMessage = false),
            )
        compose.setContent {
            MaterialTheme {
                SurveySheet(survey, onSurveyShown = {}, onSubmit = { _, response ->
                    responses.add(response)
                    PostHogNextSurveyQuestion(1, false)
                }, onClose = {})
            }
        }

        fun visibleOrder() = labels.sortedBy { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        val order = visibleOrder()
        if (shouldShuffleOptions) assertNotEquals(labels, order) else assertEquals(labels, order)
        if (hasOpenChoice) assertEquals("Other:", order.last())

        compose.onNodeWithText("B").performClick()
        assertEquals(order, visibleOrder())
        if (hasOpenChoice) {
            compose.onNodeWithText("Other:").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("Custom answer")
            assertEquals(order, visibleOrder())
        }
        compose.onNodeWithText("Submit").performClick()
        compose.onNodeWithText("Last").assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(expectedResponse()), responses)
        }
    }

    private fun expectedResponse(): PostHogSurveyResponse =
        if (isMultipleChoice) {
            PostHogSurveyResponse.MultipleChoice(if (hasOpenChoice) listOf("B", "Custom answer") else listOf("B"))
        } else {
            PostHogSurveyResponse.SingleChoice(if (hasOpenChoice) "Custom answer" else "B")
        }

    private fun question(
        id: String,
        choices: List<String>,
    ) = PostHogDisplayChoiceQuestion(
        id = id, question = id, questionDescription = null,
        questionDescriptionContentType = PostHogDisplaySurveyTextContentType.TEXT,
        isOptional = false, buttonText = "Submit", choices = choices,
        hasOpenChoice = hasOpenChoice && id == "First", shuffleOptions = shouldShuffleOptions, isMultipleChoice = isMultipleChoice,
    )

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "isMultipleChoice={0}, hasOpenChoice={1}, shouldShuffleOptions={2}")
        fun cases(): List<Array<Boolean>> =
            listOf(false, true).flatMap { isMultipleChoice ->
                listOf(false, true).flatMap {
                        hasOpenChoice ->
                    listOf(false, true).map {
                            shouldShuffleOptions ->
                        arrayOf(isMultipleChoice, hasOpenChoice, shouldShuffleOptions)
                    }
                }
            }
    }
}
