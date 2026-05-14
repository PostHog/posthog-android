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
import com.posthog.surveys.SurveyQuestionTranslation
import com.posthog.surveys.SurveyTranslation
import com.posthog.surveys.SurveyType
import org.junit.runner.RunWith
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

    private fun createQuestion(
        id: String,
        question: String,
        translations: Map<String, SurveyQuestionTranslation>? = null,
    ): SurveyQuestion {
        val map =
            mutableMapOf<String, Any?>(
                "id" to id,
                "type" to "open",
                "question" to question,
                "description" to null,
                "descriptionContentType" to "text",
                "optional" to false,
                "buttonText" to null,
                "branching" to null,
            )
        if (translations != null) {
            map["translations"] =
                translations.mapValues { (_, t) ->
                    mapOf(
                        "question" to t.question,
                        "description" to t.description,
                        "buttonText" to t.buttonText,
                        "link" to t.link,
                        "lowerBoundLabel" to t.lowerBoundLabel,
                        "upperBoundLabel" to t.upperBoundLabel,
                        "choices" to t.choices,
                    )
                }
        }
        return checkNotNull(
            serializer.deserializeList<SurveyQuestion>(listOf(map))?.firstOrNull(),
        )
    }

    private fun createSurveyWithTranslations(
        questionTranslations: Map<String, SurveyQuestionTranslation>? = null,
        surveyTranslations: Map<String, SurveyTranslation>? = null,
    ): Survey {
        return Survey(
            id = "translated-survey",
            name = "Original Name",
            type = SurveyType.POPOVER,
            questions = listOf(createQuestion("q-1", "Original question?", questionTranslations)),
            description = null,
            featureFlagKeys = null,
            linkedFlagKey = null,
            targetingFlagKey = null,
            internalTargetingFlagKey = null,
            conditions = null,
            appearance = null,
            currentIteration = null,
            currentIterationStartDate = null,
            startDate = java.util.Date(),
            endDate = null,
            schedule = null,
            translations = surveyTranslations,
        )
    }

    @Test
    fun `survey events include survey_language when override matches translation`() {
        val delegate = RecordingDelegate()
        val (integration, postHog) =
            createIntegration(
                delegate,
                overrideLanguage = "fr",
            )
        val survey =
            createSurveyWithTranslations(
                questionTranslations = mapOf("fr" to SurveyQuestionTranslation(question = "Question traduite?")),
            )

        integration.showSurvey(survey)
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
    fun `survey dismissed includes survey_language when translation applied`() {
        val delegate = RecordingDelegate()
        val (integration, postHog) =
            createIntegration(
                delegate,
                overrideLanguage = "pt-BR",
            )
        val survey =
            createSurveyWithTranslations(
                // Question translation only under "pt" — base-language fallback applies
                questionTranslations = mapOf("pt" to SurveyQuestionTranslation(question = "Pergunta?")),
            )

        integration.showSurvey(survey)
        val shownSurvey = assertNotNull(delegate.shownSurvey)
        assertNotNull(delegate.onSurveyShown).invoke(shownSurvey)
        assertNotNull(delegate.onSurveyClosed).invoke(shownSurvey)

        assertEquals("survey dismissed", postHog.event)
        // matchedKey is the original-cased dictionary key, not the request
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
        val survey =
            createSurveyWithTranslations(
                questionTranslations = mapOf("fr" to SurveyQuestionTranslation(question = "Bonjour?")),
            )

        integration.showSurvey(survey)
        val shownSurvey = assertNotNull(delegate.shownSurvey)

        // No translation applied — UI shows original
        assertEquals("Original question?", shownSurvey.questions[0].question)

        assertNotNull(delegate.onSurveyShown).invoke(shownSurvey)
        assertNull(postHog.properties?.get("\$survey_language"))

        assertNotNull(delegate.onSurveyResponse).invoke(shownSurvey, 0, PostHogSurveyResponse.Text("Hi"))
        assertNull(postHog.properties?.get("\$survey_language"))
    }

    @Test
    fun `survey without translations omits survey_language even when language override set`() {
        val delegate = RecordingDelegate()
        val (integration, postHog) =
            createIntegration(
                delegate,
                overrideLanguage = "fr",
            )
        val survey = createSurveyWithTranslations() // no translations

        integration.showSurvey(survey)
        val shownSurvey = assertNotNull(delegate.shownSurvey)
        assertNotNull(delegate.onSurveyShown).invoke(shownSurvey)
        assertNull(postHog.properties?.get("\$survey_language"))
    }
}
