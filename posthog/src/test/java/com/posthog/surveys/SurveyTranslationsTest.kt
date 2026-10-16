package com.posthog.surveys

import com.posthog.API_KEY
import com.posthog.PostHogConfig
import com.posthog.internal.PostHogSerializer
import com.posthog.internal.surveys.resolveSurveyTranslations
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class SurveyTranslationsTest {
    private val serializer: PostHogSerializer = PostHogSerializer(PostHogConfig(API_KEY))

    private fun decodeSurvey(json: String): Survey {
        return serializer.deserialize<Survey>(StringReader(json))
    }

    @Test
    fun `survey decodes translations field`() {
        val survey = decodeSurvey(SURVEY_WITH_TRANSLATIONS_JSON)
        val translations = survey.translations
        assertNotNull(translations)
        assertEquals("Bonjour", translations["fr"]?.name)
        assertEquals("Merci!", translations["fr"]?.thankYouMessageHeader)
        assertEquals("Obrigado", translations["pt"]?.thankYouMessageHeader)
    }

    @Test
    fun `question decodes translations field including choices`() {
        val survey = decodeSurvey(SURVEY_WITH_TRANSLATIONS_JSON)
        val ratingQuestion = survey.questions[0] as RatingSurveyQuestion
        val ratingTranslations = ratingQuestion.translations
        assertNotNull(ratingTranslations)
        assertEquals("Comment etait-ce?", ratingTranslations["fr"]?.question)
        assertEquals("Mauvais", ratingTranslations["fr"]?.lowerBoundLabel)

        val choiceQuestion = survey.questions[1] as SingleSurveyQuestion
        val choiceTranslations = choiceQuestion.translations
        assertNotNull(choiceTranslations)
        assertEquals(listOf("Un", "Deux"), choiceTranslations["fr"]?.choices)
    }

    @Test
    fun `resolveSurveyTranslations returns empty when target language is null`() {
        val survey = decodeSurvey(SURVEY_WITH_TRANSLATIONS_JSON)
        val resolved = resolveSurveyTranslations(survey, null)
        assertNull(resolved.matchedKey)
        assertNull(resolved.survey)
        assertTrue(resolved.questions.all { it == null })
    }

    @Test
    fun `resolveSurveyTranslations matches exact language`() {
        val survey = decodeSurvey(SURVEY_WITH_TRANSLATIONS_JSON)
        val resolved = resolveSurveyTranslations(survey, "fr")
        assertEquals("fr", resolved.matchedKey)
        assertEquals("Bonjour", resolved.survey?.name)
        assertEquals("Comment etait-ce?", resolved.questions[0]?.question)
        assertEquals(listOf("Un", "Deux"), resolved.questions[1]?.choices)
    }

    @Test
    fun `resolveSurveyTranslations falls back to base language`() {
        val survey = decodeSurvey(SURVEY_WITH_TRANSLATIONS_JSON)
        val resolved = resolveSurveyTranslations(survey, "pt-BR")
        // survey-level translations only had "pt", not "pt-BR" — should fall back
        assertEquals("pt", resolved.matchedKey)
        assertEquals("Obrigado", resolved.survey?.thankYouMessageHeader)
    }

    @Test
    fun `resolveSurveyTranslations is case insensitive and returns original key casing`() {
        val survey = decodeSurvey(SURVEY_WITH_MIXED_CASE_TRANSLATIONS_JSON)
        val resolved = resolveSurveyTranslations(survey, "FR")
        // Original survey-level key was "Fr"
        assertEquals("Fr", resolved.matchedKey)
    }

    @Test
    fun `resolveSurveyTranslations returns null matchedKey when translations exist but do not match`() {
        val survey = decodeSurvey(SURVEY_WITH_TRANSLATIONS_JSON)
        val resolved = resolveSurveyTranslations(survey, "ja")
        assertNull(resolved.matchedKey)
        assertNull(resolved.survey)
        assertTrue(resolved.questions.all { it == null })
    }

    @Test
    fun `resolveSurveyTranslations returns null matchedKey when translation field present but identical`() {
        // When the translation has all the same values, nothing actually changes — matchedKey is null
        val survey = decodeSurvey(SURVEY_WITH_NOOP_TRANSLATION_JSON)
        val resolved = resolveSurveyTranslations(survey, "fr")
        assertNull(resolved.matchedKey)
    }

    @Test
    fun `survey without translations field decodes safely`() {
        val survey = decodeSurvey(SURVEY_WITHOUT_TRANSLATIONS_JSON)
        assertNull(survey.translations)
        assertNull(survey.questions[0].translations)
        val resolved = resolveSurveyTranslations(survey, "fr")
        assertNull(resolved.matchedKey)
    }

    @Test
    fun `display survey applies translation fields with fallback to original`() {
        val survey = decodeSurvey(SURVEY_WITH_TRANSLATIONS_JSON)
        val resolved = resolveSurveyTranslations(survey, "fr")

        val display =
            PostHogDisplaySurvey.toDisplaySurvey(
                survey,
                surveyTranslation = resolved.survey,
                questionTranslations = resolved.questions,
            )

        // Translated survey-level name
        assertEquals("Bonjour", display.name)
        // Translated rating question text + bound labels
        val rating = display.questions[0] as PostHogDisplayRatingQuestion
        assertEquals("Comment etait-ce?", rating.question)
        assertEquals("Mauvais", rating.lowerBoundLabel)
        // upperBoundLabel was not translated — falls back to original
        assertEquals("Great", rating.upperBoundLabel)
        // Translated choices
        val choice = display.questions[1] as PostHogDisplayChoiceQuestion
        assertEquals(listOf("Un", "Deux"), choice.choices)
        // Translated thank you message
        assertEquals("Merci!", display.appearance?.thankYouMessageHeader)
        // Translated intro screen fields; untranslated description falls back to original
        assertEquals("Bienvenue!", display.appearance?.introScreenHeader)
        assertEquals("Commencer", display.appearance?.introScreenButtonText)
        assertEquals("Two quick questions.", display.appearance?.introScreenDescription)
    }

    @Test
    fun `display survey without translations renders original fields`() {
        val survey = decodeSurvey(SURVEY_WITH_TRANSLATIONS_JSON)
        val display = PostHogDisplaySurvey.toDisplaySurvey(survey)

        assertEquals("Hello", display.name)
        val rating = display.questions[0] as PostHogDisplayRatingQuestion
        assertEquals("How was it?", rating.question)
        assertEquals("Bad", rating.lowerBoundLabel)
        assertEquals("Great", rating.upperBoundLabel)
        val choice = display.questions[1] as PostHogDisplayChoiceQuestion
        assertEquals(listOf("One", "Two"), choice.choices)
        assertEquals("Thanks!", display.appearance?.thankYouMessageHeader)
        assertEquals(true, display.appearance?.displayIntroScreen)
        assertEquals("Welcome!", display.appearance?.introScreenHeader)
    }

    @Test
    fun `resolveSurveyTranslations does not mutate the original survey`() {
        val survey = decodeSurvey(SURVEY_WITH_TRANSLATIONS_JSON)
        val originalName = survey.name
        val originalQuestionText = survey.questions[0].question
        val originalChoices = (survey.questions[1] as SingleSurveyQuestion).choices

        resolveSurveyTranslations(survey, "fr")

        assertEquals(originalName, survey.name)
        assertEquals(originalQuestionText, survey.questions[0].question)
        assertSame(originalChoices, (survey.questions[1] as SingleSurveyQuestion).choices)
    }

    @Test
    fun `blank translated button text falls back instead of rendering an empty label`() {
        for (blank in listOf("", "   ")) {
            val display =
                displayInJapanese(
                    buttonTextSurveyJson(
                        baseButtonText = "Send",
                        translatedButtonText = blank,
                        baseCloseText = "Done",
                        translatedCloseText = blank,
                        baseIntroText = "Start",
                        translatedIntroText = blank,
                    ),
                )

            assertEquals("translated question", display.questions[0].question)
            assertEquals("Send", display.questions[0].buttonText)
            assertEquals("translated header", display.appearance?.thankYouMessageHeader)
            assertEquals("Done", display.appearance?.thankYouMessageCloseButtonText)
            assertEquals("Start", display.appearance?.introScreenButtonText)
        }
    }

    @Test
    fun `blank button text everywhere resolves to null so the default label applies`() {
        val display =
            displayInJapanese(
                buttonTextSurveyJson(
                    baseButtonText = null,
                    translatedButtonText = "",
                    baseCloseText = "",
                    translatedCloseText = "",
                    baseIntroText = null,
                    translatedIntroText = "",
                    submitButtonText = "",
                ),
            )

        assertNull(display.questions[0].buttonText)
        assertNull(display.appearance?.submitButtonText)
        assertNull(display.appearance?.thankYouMessageCloseButtonText)
        assertNull(display.appearance?.introScreenButtonText)
    }

    @Test
    fun `translated button text still wins when it is not blank`() {
        val display =
            displayInJapanese(
                buttonTextSurveyJson(
                    baseButtonText = "Send",
                    translatedButtonText = "translated send",
                    baseCloseText = "Done",
                    translatedCloseText = "translated done",
                    baseIntroText = "Start",
                    translatedIntroText = "translated start",
                ),
            )

        assertEquals("translated send", display.questions[0].buttonText)
        assertEquals("translated done", display.appearance?.thankYouMessageCloseButtonText)
        assertEquals("translated start", display.appearance?.introScreenButtonText)
    }

    @Test
    fun `a translation that only blanks button labels is a no-op`() {
        val survey =
            decodeSurvey(
                buttonTextSurveyJson(
                    baseButtonText = "Send",
                    translatedButtonText = "",
                    baseCloseText = "Done",
                    translatedCloseText = "",
                    baseIntroText = "Start",
                    translatedIntroText = "",
                    translateOtherFields = false,
                ),
            )

        val resolved = resolveSurveyTranslations(survey, "ja")

        assertNull(resolved.matchedKey)
        assertNull(resolved.survey)
        assertTrue(resolved.questions.all { it == null })
    }

    private fun displayInJapanese(json: String): PostHogDisplaySurvey {
        val survey = decodeSurvey(json)
        val resolved = resolveSurveyTranslations(survey, "ja")
        return PostHogDisplaySurvey.toDisplaySurvey(
            survey,
            surveyTranslation = resolved.survey,
            questionTranslations = resolved.questions,
        )
    }

    // A null argument omits the key from the JSON.
    private fun buttonTextSurveyJson(
        baseButtonText: String?,
        translatedButtonText: String?,
        baseCloseText: String?,
        translatedCloseText: String?,
        baseIntroText: String?,
        translatedIntroText: String?,
        submitButtonText: String? = null,
        translateOtherFields: Boolean = true,
    ): String {
        fun field(
            key: String,
            value: String?,
        ) = value?.let { ", \"$key\": \"$it\"" } ?: ""
        val translatedQuestion = if (translateOtherFields) "translated question" else "base question"
        val translatedHeader = if (translateOtherFields) "translated header" else "base header"
        val appearanceButtons =
            field("submitButtonText", submitButtonText) +
                field("thankYouMessageCloseButtonText", baseCloseText) +
                field("introScreenButtonText", baseIntroText)
        val translatedButtons =
            field("thankYouMessageCloseButtonText", translatedCloseText) +
                field("introScreenButtonText", translatedIntroText)
        return """
            {
              "id": "button-text-survey",
              "name": "Hello",
              "type": "popover",
              "appearance": { "thankYouMessageHeader": "base header"$appearanceButtons },
              "translations": {
                "ja": { "thankYouMessageHeader": "$translatedHeader"$translatedButtons }
              },
              "questions": [
                {
                  "type": "open",
                  "id": "q1",
                  "question": "base question"${field("buttonText", baseButtonText)},
                  "translations": {
                    "ja": { "question": "$translatedQuestion"${field("buttonText", translatedButtonText)} }
                  }
                }
              ]
            }
            """.trimIndent()
    }

    companion object {
        private val SURVEY_WITH_TRANSLATIONS_JSON =
            """
            {
              "id": "s1",
              "name": "Hello",
              "type": "popover",
              "description": null,
              "feature_flag_keys": null,
              "linked_flag_key": null,
              "targeting_flag_key": null,
              "internal_targeting_flag_key": null,
              "conditions": null,
              "current_iteration": null,
              "current_iteration_start_date": null,
              "start_date": null,
              "end_date": null,
              "schedule": null,
              "appearance": {
                "thankYouMessageHeader": "Thanks!",
                "thankYouMessageDescription": "We appreciate it",
                "thankYouMessageCloseButtonText": "Close",
                "displayIntroScreen": true,
                "introScreenHeader": "Welcome!",
                "introScreenDescription": "Two quick questions.",
                "introScreenButtonText": "Get started",
                "borderColor": "#000"
              },
              "translations": {
                "fr": {
                  "name": "Bonjour",
                  "thankYouMessageHeader": "Merci!",
                  "introScreenHeader": "Bienvenue!",
                  "introScreenButtonText": "Commencer"
                },
                "pt": {
                  "thankYouMessageHeader": "Obrigado"
                }
              },
              "questions": [
                {
                  "type": "rating",
                  "id": "q1",
                  "question": "How was it?",
                  "description": null,
                  "scale": 5,
                  "display": "number",
                  "lowerBoundLabel": "Bad",
                  "upperBoundLabel": "Great",
                  "translations": {
                    "fr": { "question": "Comment etait-ce?", "lowerBoundLabel": "Mauvais" }
                  }
                },
                {
                  "type": "single_choice",
                  "id": "q2",
                  "question": "Pick one",
                  "choices": ["One", "Two"],
                  "translations": {
                    "fr": { "choices": ["Un", "Deux"] }
                  }
                }
              ]
            }
            """.trimIndent()

        private val SURVEY_WITH_MIXED_CASE_TRANSLATIONS_JSON =
            """
            {
              "id": "s2",
              "name": "Hello",
              "type": "popover",
              "description": null,
              "feature_flag_keys": null,
              "linked_flag_key": null,
              "targeting_flag_key": null,
              "internal_targeting_flag_key": null,
              "conditions": null,
              "current_iteration": null,
              "current_iteration_start_date": null,
              "start_date": null,
              "end_date": null,
              "schedule": null,
              "appearance": null,
              "translations": {
                "Fr": { "name": "Salut" }
              },
              "questions": [
                { "type": "open", "id": "q1", "question": "Free text", "description": null }
              ]
            }
            """.trimIndent()

        private val SURVEY_WITH_NOOP_TRANSLATION_JSON =
            """
            {
              "id": "s3",
              "name": "Hello",
              "type": "popover",
              "description": null,
              "feature_flag_keys": null,
              "linked_flag_key": null,
              "targeting_flag_key": null,
              "internal_targeting_flag_key": null,
              "conditions": null,
              "current_iteration": null,
              "current_iteration_start_date": null,
              "start_date": null,
              "end_date": null,
              "schedule": null,
              "appearance": null,
              "translations": {
                "fr": { "name": "Hello" }
              },
              "questions": [
                { "type": "open", "id": "q1", "question": "Free text", "description": null }
              ]
            }
            """.trimIndent()

        private val SURVEY_WITHOUT_TRANSLATIONS_JSON =
            """
            {
              "id": "s4",
              "name": "Hello",
              "type": "popover",
              "description": null,
              "feature_flag_keys": null,
              "linked_flag_key": null,
              "targeting_flag_key": null,
              "internal_targeting_flag_key": null,
              "conditions": null,
              "current_iteration": null,
              "current_iteration_start_date": null,
              "start_date": null,
              "end_date": null,
              "schedule": null,
              "appearance": null,
              "questions": [
                { "type": "open", "id": "q1", "question": "Free text", "description": null }
              ]
            }
            """.trimIndent()
    }
}
