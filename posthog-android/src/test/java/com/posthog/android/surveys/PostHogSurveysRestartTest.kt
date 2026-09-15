package com.posthog.android.surveys

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.PostHog
import com.posthog.PostHogBeforeSend
import com.posthog.PostHogConfig
import com.posthog.PostHogInterface
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.internal.PostHogSharedPreferences
import com.posthog.internal.PostHogSerializer
import com.posthog.surveys.PostHogSurveyResponse
import com.posthog.surveys.Survey
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(AndroidJUnit4::class)
internal class PostHogSurveysRestartTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val serializer = PostHogSerializer(PostHogConfig("test-api-key"))
    private val delegate = RecordingSurveyDelegate()
    private val sent = mutableListOf<Map<String, Any?>>()
    private var dismissed: Map<String, Any?>? = null

    @Test
    fun `unfinished responses survive SDK close and fresh setup with translated wording`() {
        for ((enabled, dismiss) in listOf(true to false, false to false, null to false, true to true)) {
            sent.clear()
            dismissed = null
            val preferences = PostHogSharedPreferences(context, PostHogAndroidConfig("survey-resume-test"))
            preferences.clear()
            val survey = survey(enabled)
            val (firstSdk, first) = setup("fr")
            val identity = firstSdk.distinctId()
            try {
                first.showSurvey(survey)
                val display = assertNotNull(delegate.shownSurvey)
                assertNotNull(delegate.onSurveyShown).invoke(display)
                assertNotNull(delegate.onSurveyResponse).invoke(display, display.initialQuestionIndex, PostHogSurveyResponse.Text("Saved"))
                assertEquals(if (enabled == true) 1 else 0, sent.size)
            } finally {
                firstSdk.close()
            }
            val submissionId = sent.firstOrNull()?.get("\$survey_submission_id")
            val (nextSdk, resumed) = setup("es")
            try {
                assertEquals(identity, nextSdk.distinctId())
                resumed.showSurvey(survey(enabled, revised = true))
                val restored = assertNotNull(delegate.shownSurvey)
                assertEquals(1, restored.initialQuestionIndex)
                assertNotNull(delegate.onSurveyShown).invoke(restored)
                if (dismiss) {
                    assertNotNull(delegate.onSurveyClosed).invoke(restored)
                    assertDismissed(submissionId)
                    continue
                }
                assertNotNull(
                    delegate.onSurveyResponse,
                ).invoke(restored, restored.initialQuestionIndex, PostHogSurveyResponse.Text("Final"))
                assertCompleted(enabled, submissionId)
                assertNotNull(delegate.onSurveyClosed).invoke(restored)
                resumed.showSurvey(survey)
                assertEquals(0, assertNotNull(delegate.shownSurvey).initialQuestionIndex)
            } finally {
                nextSdk.close()
                preferences.clear()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun setup(language: String): Pair<PostHogInterface, PostHogSurveysIntegration> {
        val config =
            PostHogConfig("survey-resume-test", "http://127.0.0.1:1").apply {
                cachePreferences =
                    PostHogSharedPreferences(
                        this@PostHogSurveysRestartTest.context,
                        PostHogAndroidConfig(apiKey),
                    )
                preloadFeatureFlags = false
                remoteConfig = false
                surveys = true
                surveysConfig.surveysDelegate = delegate
                surveysConfig.overrideDisplayLanguage = language
                addBeforeSend(
                    PostHogBeforeSend { event ->
                        if (event.event == "survey sent") sent.add(assertNotNull(event.properties).toMap())
                        if (event.event == "survey dismissed") dismissed = assertNotNull(event.properties).toMap()
                        null
                    },
                )
            }
        val integration = PostHogSurveysIntegration(context, config)
        config.addIntegration(integration)
        return PostHog.with(config) to integration
    }

    private val questionData =
        listOf(
            mapOf(
                "id" to "first",
                "type" to "open",
                "question" to "First?",
                "translations" to mapOf("fr" to mapOf("question" to "Ancienne question?")),
            ),
            mapOf(
                "id" to "second",
                "type" to "open",
                "question" to "Second?",
                "translations" to mapOf("es" to mapOf("question" to "Nueva pregunta?")),
            ),
        )

    private fun survey(
        enabled: Boolean?,
        revised: Boolean = false,
    ): Survey =
        assertNotNull(
            serializer.deserializeList<Survey>(
                listOf(
                    mapOf(
                        "id" to "partial-survey",
                        "name" to "Partial survey",
                        "type" to "popover",
                        "questions" to questionData.map { if (revised) it + ("question" to "Revised wording?") else it },
                        "enable_partial_responses" to enabled,
                    ),
                ),
            )?.firstOrNull(),
        )

    private fun assertDismissed(submissionId: Any?) {
        val properties = assertNotNull(dismissed)
        assertEquals("fr", properties["\$survey_language"])
        assertEquals(submissionId, properties["\$survey_submission_id"])
        assertEquals("Saved", properties["\$survey_response_first"])
        assertEquals(
            listOf(
                mapOf("id" to "first", "question" to "Ancienne question?", "response" to "Saved"),
                mapOf("id" to "second", "question" to "Nueva pregunta?"),
            ),
            properties["\$survey_questions"],
        )
        assertEquals(1, sent.size)
    }

    private fun assertCompleted(
        enabled: Boolean?,
        submissionId: Any?,
    ) {
        assertEquals(if (enabled == true) 2 else 1, sent.size)
        val properties = sent.last()
        assertEquals("Saved", properties["\$survey_response"])
        assertEquals("Saved", properties["\$survey_response_first"])
        assertEquals("Final", properties["\$survey_response_1"])
        assertEquals("Final", properties["\$survey_response_second"])
        assertEquals(true, properties["\$survey_completed"])
        assertEquals("es", properties["\$survey_language"])
        assertEquals(
            listOf(
                mapOf("id" to "first", "question" to "Ancienne question?", "response" to "Saved"),
                mapOf("id" to "second", "question" to "Nueva pregunta?", "response" to "Final"),
            ),
            properties["\$survey_questions"],
        )
        assertNotNull(properties["\$survey_submission_id"])
        if (enabled == true) assertEquals(submissionId, properties["\$survey_submission_id"])
    }
}
