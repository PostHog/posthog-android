package com.posthog.android.surveys

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.PostHogConfig
import com.posthog.android.PostHogFake
import com.posthog.internal.PostHogSerializer
import com.posthog.surveys.OnPostHogSurveyClosed
import com.posthog.surveys.OnPostHogSurveyResponse
import com.posthog.surveys.OnPostHogSurveyShown
import com.posthog.surveys.PostHogDisplaySurvey
import com.posthog.surveys.PostHogSurveyResponse
import com.posthog.surveys.PostHogSurveysDelegate
import com.posthog.surveys.Survey
import com.posthog.surveys.SurveyQuestion
import com.posthog.surveys.SurveyType
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
internal class PostHogSurveysDismissedEventTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val serializer = PostHogSerializer(PostHogConfig("test-api-key"))

    private class RecordingDelegate : PostHogSurveysDelegate {
        var shownSurvey: PostHogDisplaySurvey? = null
        var onSurveyShown: OnPostHogSurveyShown? = null
        var onSurveyResponse: OnPostHogSurveyResponse? = null
        var onSurveyClosed: OnPostHogSurveyClosed? = null

        override fun renderSurvey(
            survey: PostHogDisplaySurvey,
            onSurveyShown: OnPostHogSurveyShown,
            onSurveyResponse: OnPostHogSurveyResponse,
            onSurveyClosed: OnPostHogSurveyClosed,
        ) {
            shownSurvey = survey
            this.onSurveyShown = onSurveyShown
            this.onSurveyResponse = onSurveyResponse
            this.onSurveyClosed = onSurveyClosed
        }

        override fun cleanupSurveys() {}
    }

    private fun createIntegration(delegate: RecordingDelegate): Pair<PostHogSurveysIntegration, PostHogFake> {
        val config =
            PostHogConfig("test-api-key").apply {
                surveys = true
                surveysConfig.surveysDelegate = delegate
            }

        val integration = PostHogSurveysIntegration(context, config)
        val postHog = PostHogFake()
        integration.install(postHog)

        return integration to postHog
    }

    private fun createQuestion(
        id: String,
        question: String,
    ): SurveyQuestion {
        return checkNotNull(
            serializer.deserializeList<SurveyQuestion>(
                listOf(
                    mapOf(
                        "id" to id,
                        "type" to "open",
                        "question" to question,
                        "description" to null,
                        "descriptionContentType" to "text",
                        "optional" to false,
                        "buttonText" to null,
                        "branching" to null,
                    ),
                ),
            )?.firstOrNull(),
        )
    }

    private fun createSurvey(
        id: String = "test-survey-id",
        name: String = "Test Survey",
        currentIteration: Int? = null,
    ): Survey {
        return Survey(
            id = id,
            name = name,
            type = SurveyType.POPOVER,
            questions =
                listOf(
                    createQuestion("question-1", "How satisfied are you?"),
                    createQuestion("question-2", "Any additional comments?"),
                ),
            description = null,
            featureFlagKeys = null,
            linkedFlagKey = null,
            targetingFlagKey = null,
            internalTargetingFlagKey = null,
            conditions = null,
            appearance = null,
            currentIteration = currentIteration,
            currentIterationStartDate = null,
            startDate = java.util.Date(),
            endDate = null,
            schedule = null,
        )
    }

    @Test
    fun `survey sent includes legacy and question id response keys`() {
        val delegate = RecordingDelegate()
        val (integration, postHog) = createIntegration(delegate)
        val survey = createSurvey(id = "sent-survey", name = "Sent Survey")

        integration.showSurvey(survey)

        val shownSurvey = assertNotNull(delegate.shownSurvey)
        assertNotNull(delegate.onSurveyShown).invoke(shownSurvey)
        assertNotNull(delegate.onSurveyResponse).invoke(shownSurvey, 0, PostHogSurveyResponse.Text("Great product!"))
        assertNotNull(delegate.onSurveyResponse).invoke(shownSurvey, 1, PostHogSurveyResponse.Text("Keep it up!"))

        assertEquals("survey sent", postHog.event)

        val properties = assertNotNull(postHog.properties)
        assertEquals("Sent Survey", properties["\$survey_name"])
        assertEquals("sent-survey", properties["\$survey_id"])
        assertEquals("Great product!", properties["\$survey_response"])
        assertEquals("Keep it up!", properties["\$survey_response_1"])
        assertEquals("Great product!", properties["\$survey_response_question-1"])
        assertEquals("Keep it up!", properties["\$survey_response_question-2"])
        assertEquals(
            listOf(
                mapOf(
                    "id" to "question-1",
                    "question" to "How satisfied are you?",
                    "response" to "Great product!",
                ),
                mapOf(
                    "id" to "question-2",
                    "question" to "Any additional comments?",
                    "response" to "Keep it up!",
                ),
            ),
            properties["\$survey_questions"],
        )

        val setProperties = properties["\$set"] as? Map<*, *>
        assertEquals(true, setProperties?.get("\$survey_responded/sent-survey"))
    }

    @Test
    fun `survey dismissed includes responses and marks partial completion when there are answers`() {
        val delegate = RecordingDelegate()
        val (integration, postHog) = createIntegration(delegate)
        val survey = createSurvey()

        integration.showSurvey(survey)

        val shownSurvey = assertNotNull(delegate.shownSurvey)
        assertNotNull(delegate.onSurveyShown).invoke(shownSurvey)
        assertNotNull(delegate.onSurveyResponse).invoke(shownSurvey, 0, PostHogSurveyResponse.Text("Great product!"))
        assertNotNull(delegate.onSurveyClosed).invoke(shownSurvey)

        assertEquals("survey dismissed", postHog.event)

        val properties = assertNotNull(postHog.properties)
        assertEquals("Test Survey", properties["\$survey_name"])
        assertEquals("test-survey-id", properties["\$survey_id"])
        assertEquals(true, properties["\$survey_partially_completed"])
        assertEquals("Great product!", properties["\$survey_response"])
        assertEquals("Great product!", properties["\$survey_response_question-1"])
        assertEquals(
            listOf(
                mapOf(
                    "id" to "question-1",
                    "question" to "How satisfied are you?",
                    "response" to "Great product!",
                ),
                mapOf(
                    "id" to "question-2",
                    "question" to "Any additional comments?",
                ),
            ),
            properties["\$survey_questions"],
        )

        val setProperties = properties["\$set"] as? Map<*, *>
        assertEquals(true, setProperties?.get("\$survey_dismissed/test-survey-id"))
    }

    @Test
    fun `survey dismissed marks partial completion false when there are no answers`() {
        val delegate = RecordingDelegate()
        val (integration, postHog) = createIntegration(delegate)
        val survey = createSurvey(id = "empty-dismissed-survey", name = "Empty Dismissed Survey")

        integration.showSurvey(survey)

        val shownSurvey = assertNotNull(delegate.shownSurvey)
        assertNotNull(delegate.onSurveyShown).invoke(shownSurvey)
        assertNotNull(delegate.onSurveyClosed).invoke(shownSurvey)

        assertEquals("survey dismissed", postHog.event)

        val properties = assertNotNull(postHog.properties)
        assertEquals(false, properties["\$survey_partially_completed"])
        assertNull(properties["\$survey_response"])
        assertEquals(
            listOf(
                mapOf(
                    "id" to "question-1",
                    "question" to "How satisfied are you?",
                ),
                mapOf(
                    "id" to "question-2",
                    "question" to "Any additional comments?",
                ),
            ),
            properties["\$survey_questions"],
        )

        val setProperties = properties["\$set"] as? Map<*, *>
        assertEquals(true, setProperties?.get("\$survey_dismissed/empty-dismissed-survey"))
        assertNull(properties["\$survey_response_1"])
        assertNull(properties["\$survey_response_question-1"])
    }
}
