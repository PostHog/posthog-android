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
import org.junit.runner.RunWith
import java.io.StringReader
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
internal class PostHogSurveysTranslationsTest {
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

    private fun createIntegration(
        delegate: RecordingDelegate,
        overrideLanguage: String? = null,
    ): Pair<PostHogSurveysIntegration, PostHogFake> {
        val config =
            PostHogConfig("test-api-key").apply {
                surveys = true
                surveysConfig.surveysDelegate = delegate
                surveysConfig.overrideDisplayLanguage = overrideLanguage
            }

        val integration = PostHogSurveysIntegration(context, config)
        val postHog = PostHogFake()
        integration.install(postHog)

        return integration to postHog
    }

    private fun decodeSurvey(json: String): Survey {
        val survey = serializer.deserialize<Survey>(StringReader(json))
        // Round-trip the survey through copy() so startDate is set to now (matching constructor pattern)
        return survey.copy(startDate = Date())
    }

    @Test
    fun `survey events include survey_language when override matches translation`() {
        val delegate = RecordingDelegate()
        val (integration, postHog) =
            createIntegration(
                delegate,
                overrideLanguage = "fr",
            )

        integration.showSurvey(decodeSurvey(SURVEY_WITH_FR_QUESTION_TRANSLATION))
        val shownSurvey = assertNotNull(delegate.shownSurvey)

        // Translated text reaches the delegate
        assertEquals("Question traduite?", shownSurvey.questions[0].question)

        // survey shown event has $survey_language
        assertNotNull(delegate.onSurveyShown).invoke(shownSurvey)
        assertEquals("survey shown", postHog.event)
        assertEquals("fr", postHog.properties?.get("\$survey_language"))

        // survey sent event has $survey_language and translated question text in $survey_questions
        assertNotNull(delegate.onSurveyResponse).invoke(shownSurvey, 0, PostHogSurveyResponse.Text("Response"))
        assertEquals("survey sent", postHog.event)
        assertEquals("fr", postHog.properties?.get("\$survey_language"))

        @Suppress("UNCHECKED_CAST")
        val questions = postHog.properties?.get("\$survey_questions") as? List<Map<String, Any>>
        assertEquals("Question traduite?", questions?.firstOrNull()?.get("question"))
    }

    @Test
    fun `survey dismissed includes survey_language when translation applied via base language fallback`() {
        val delegate = RecordingDelegate()
        val (integration, postHog) =
            createIntegration(
                delegate,
                overrideLanguage = "pt-BR",
            )

        integration.showSurvey(decodeSurvey(SURVEY_WITH_PT_QUESTION_TRANSLATION))
        val shownSurvey = assertNotNull(delegate.shownSurvey)
        assertNotNull(delegate.onSurveyShown).invoke(shownSurvey)
        assertNotNull(delegate.onSurveyClosed).invoke(shownSurvey)

        assertEquals("survey dismissed", postHog.event)
        // matchedKey is the original-cased dictionary key, not the requested target
        assertEquals("pt", postHog.properties?.get("\$survey_language"))
    }

    @Test
    fun `survey events omit survey_language when no translation matches`() {
        val delegate = RecordingDelegate()
        val (integration, postHog) =
            createIntegration(
                delegate,
                overrideLanguage = "ja",
            )

        integration.showSurvey(decodeSurvey(SURVEY_WITH_FR_QUESTION_TRANSLATION))
        val shownSurvey = assertNotNull(delegate.shownSurvey)

        // No translation applied — UI shows original
        assertEquals("Original question?", shownSurvey.questions[0].question)

        assertNotNull(delegate.onSurveyShown).invoke(shownSurvey)
        assertNull(postHog.properties?.get("\$survey_language"))

        assertNotNull(delegate.onSurveyResponse).invoke(shownSurvey, 0, PostHogSurveyResponse.Text("Hi"))
        assertNull(postHog.properties?.get("\$survey_language"))
    }

    @Test
    fun `survey without translations omits survey_language even when override is set`() {
        val delegate = RecordingDelegate()
        val (integration, postHog) =
            createIntegration(
                delegate,
                overrideLanguage = "fr",
            )

        integration.showSurvey(decodeSurvey(SURVEY_WITHOUT_TRANSLATIONS))
        val shownSurvey = assertNotNull(delegate.shownSurvey)
        assertNotNull(delegate.onSurveyShown).invoke(shownSurvey)
        assertNull(postHog.properties?.get("\$survey_language"))
    }

    private companion object {
        private val SURVEY_WITH_FR_QUESTION_TRANSLATION =
            """
            {
              "id": "translated-survey",
              "name": "Original Name",
              "type": "popover",
              "description": null,
              "feature_flag_keys": null,
              "linked_flag_key": null,
              "targeting_flag_key": null,
              "internal_targeting_flag_key": null,
              "conditions": null,
              "appearance": null,
              "current_iteration": null,
              "current_iteration_start_date": null,
              "start_date": "2025-01-01T00:00:00.000Z",
              "end_date": null,
              "schedule": null,
              "questions": [
                {
                  "id": "q-1",
                  "type": "open",
                  "question": "Original question?",
                  "description": null,
                  "descriptionContentType": "text",
                  "optional": false,
                  "buttonText": null,
                  "branching": null,
                  "translations": {
                    "fr": { "question": "Question traduite?" }
                  }
                }
              ]
            }
            """.trimIndent()

        private val SURVEY_WITH_PT_QUESTION_TRANSLATION =
            """
            {
              "id": "translated-survey",
              "name": "Original Name",
              "type": "popover",
              "description": null,
              "feature_flag_keys": null,
              "linked_flag_key": null,
              "targeting_flag_key": null,
              "internal_targeting_flag_key": null,
              "conditions": null,
              "appearance": null,
              "current_iteration": null,
              "current_iteration_start_date": null,
              "start_date": "2025-01-01T00:00:00.000Z",
              "end_date": null,
              "schedule": null,
              "questions": [
                {
                  "id": "q-1",
                  "type": "open",
                  "question": "Original question?",
                  "description": null,
                  "descriptionContentType": "text",
                  "optional": false,
                  "buttonText": null,
                  "branching": null,
                  "translations": {
                    "pt": { "question": "Pergunta?" }
                  }
                }
              ]
            }
            """.trimIndent()

        private val SURVEY_WITHOUT_TRANSLATIONS =
            """
            {
              "id": "translated-survey",
              "name": "Original Name",
              "type": "popover",
              "description": null,
              "feature_flag_keys": null,
              "linked_flag_key": null,
              "targeting_flag_key": null,
              "internal_targeting_flag_key": null,
              "conditions": null,
              "appearance": null,
              "current_iteration": null,
              "current_iteration_start_date": null,
              "start_date": "2025-01-01T00:00:00.000Z",
              "end_date": null,
              "schedule": null,
              "questions": [
                {
                  "id": "q-1",
                  "type": "open",
                  "question": "Original question?",
                  "description": null,
                  "descriptionContentType": "text",
                  "optional": false,
                  "buttonText": null,
                  "branching": null
                }
              ]
            }
            """.trimIndent()
    }
}
