package com.posthog.android.surveys

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.PostHogConfig
import com.posthog.PostHogFake
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.surveys.PostHogNextSurveyQuestion
import com.posthog.surveys.PostHogSurveyResponse
import com.posthog.surveys.Survey
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(AndroidJUnit4::class)
internal class PostHogSurveysBranchingTest {
    private fun assertBranch(
        branching: Map<String, Any>?,
        expected: PostHogNextSurveyQuestion,
        response: PostHogSurveyResponse = PostHogSurveyResponse.Text("answer"),
        questionType: String = "open",
        currentIndex: Int = 0,
    ) {
        val delegate = RecordingSurveyDelegate()
        val config =
            PostHogConfig("branching-test").apply {
                cachePreferences = PostHogMemoryPreferences()
                surveys = true
                surveysConfig.surveysDelegate = delegate
            }
        val questions =
            (0..2).map { index ->
                mapOf(
                    "id" to "q$index",
                    "type" to if (index == currentIndex) questionType else "open",
                    "question" to "Question $index",
                    "branching" to if (index == currentIndex) branching else null,
                    "choices" to listOf("Yes", "No", "Maybe"),
                    "scale" to 5,
                    "display" to "number",
                )
            }
        val survey =
            assertNotNull(
                config.serializer.deserializeList<Survey>(
                    listOf(mapOf("id" to "branching", "name" to "Branching", "type" to "popover", "questions" to questions)),
                ),
            ).single()
        val integration = PostHogSurveysIntegration(ApplicationProvider.getApplicationContext(), config)
        integration.install(PostHogFake())
        try {
            integration.showSurvey(survey)
            val display = assertNotNull(delegate.shownSurvey)
            assertNotNull(delegate.onSurveyShown).invoke(display)
            val actual = assertNotNull(assertNotNull(delegate.onSurveyResponse).invoke(display, currentIndex, response))
            assertEquals(expected.questionIndex, actual.questionIndex)
            assertEquals(expected.isSurveyCompleted, actual.isSurveyCompleted)
        } finally {
            integration.uninstall()
        }
    }

    @Test
    fun `next and absent branching advance and complete at the last question`() {
        for (branching in listOf(null, mapOf("type" to "next"))) {
            assertBranch(branching, PostHogNextSurveyQuestion(1, false))
            assertBranch(branching, PostHogNextSurveyQuestion(2, true), currentIndex = 2)
        }
    }

    @Test
    fun `end branching completes without advancing`() {
        assertBranch(mapOf("type" to "end"), PostHogNextSurveyQuestion(0, true))
    }

    @Test
    fun `specific question branching skips to its destination`() {
        assertBranch(mapOf("type" to "specific_question", "index" to 2), PostHogNextSurveyQuestion(2, false))
    }

    @Test
    fun `single choice branching maps choice labels to configured indices`() {
        val branching = mapOf("type" to "response_based", "responseValues" to mapOf("0" to 2, "1" to "end"))
        assertBranch(branching, PostHogNextSurveyQuestion(2, false), PostHogSurveyResponse.SingleChoice("Yes"), "single_choice")
        assertBranch(branching, PostHogNextSurveyQuestion(2, true), PostHogSurveyResponse.SingleChoice("No"), "single_choice")
    }

    @Test
    fun `unmapped and unknown choices fall back to the next question`() {
        val branching = mapOf("type" to "response_based", "responseValues" to mapOf("0" to 2))
        for (choice in listOf("Maybe", "unknown")) {
            assertBranch(branching, PostHogNextSurveyQuestion(1, false), PostHogSurveyResponse.SingleChoice(choice), "single_choice")
        }
    }

    @Test
    fun `rating branching uses negative neutral and positive buckets`() {
        val branching =
            mapOf("type" to "response_based", "responseValues" to mapOf("negative" to 1, "neutral" to 2, "positive" to "end"))
        assertBranch(branching, PostHogNextSurveyQuestion(1, false), PostHogSurveyResponse.Rating(1), "rating")
        assertBranch(branching, PostHogNextSurveyQuestion(2, false), PostHogSurveyResponse.Rating(3), "rating")
        assertBranch(branching, PostHogNextSurveyQuestion(2, true), PostHogSurveyResponse.Rating(5), "rating")
    }

    @Test
    fun `unsupported response based question falls back to next`() {
        assertBranch(
            mapOf("type" to "response_based", "responseValues" to mapOf("answer" to "end")),
            PostHogNextSurveyQuestion(1, false),
        )
    }
}
