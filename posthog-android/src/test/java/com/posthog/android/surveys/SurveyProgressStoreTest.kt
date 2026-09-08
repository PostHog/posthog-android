package com.posthog.android.surveys

import com.posthog.PostHogConfig
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogPreferences
import com.posthog.internal.PostHogSerializer
import com.posthog.surveys.PostHogSurveyResponse
import com.posthog.surveys.Survey
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

internal class SurveyProgressStoreTest {
    private val preferences = PostHogMemoryPreferences()
    private val config = PostHogConfig("progress-test").apply { cachePreferences = preferences }
    private val serializer = PostHogSerializer(config)
    private val store = SurveyProgressStore(config)
    private val survey =
        checkNotNull(
            serializer.deserializeList<Survey>(
                listOf(
                    mapOf(
                        "id" to "survey",
                        "name" to "Survey",
                        "type" to "popover",
                        "current_iteration" to 1,
                        "questions" to listOf(mapOf("id" to "first", "type" to "open", "question" to "First?")),
                    ),
                ),
            )?.first(),
        ).copy(startDate = java.util.Date())

    @Test
    fun `all response kinds round trip including skipped optional answers`() {
        val responses =
            listOf(
                PostHogSurveyResponse.Text("Saved"), PostHogSurveyResponse.Text(null),
                PostHogSurveyResponse.Rating(4), PostHogSurveyResponse.Rating(null),
                PostHogSurveyResponse.SingleChoice("A"), PostHogSurveyResponse.SingleChoice(null),
                PostHogSurveyResponse.MultipleChoice(listOf("A", "B")), PostHogSurveyResponse.MultipleChoice(null),
                PostHogSurveyResponse.Link(true), PostHogSurveyResponse.Link(false),
            )
        for (response in responses) {
            val progress =
                SurveyProgress(
                    "submission",
                    store.questionOrder(survey),
                    responses = mapOf("answer" to StoredSurveyResponse.from(response)),
                    questionText = mapOf(0 to "Original text"),
                    language = "fr",
                )
            store.save(survey, progress)
            val restored = assertNotNull(SurveyProgressStore(config).load(survey))
            assertEquals(response, restored.responses["answer"]?.toResponse())
            assertEquals("Original text", restored.questionText[0])
            assertEquals("fr", restored.language)
        }
    }

    @Test
    fun `corrupt or incompatible progress is discarded`() {
        val valid = assertNotNull(serializer.serializeObject(SurveyProgress("submission", store.questionOrder(survey))))
        for (invalid in listOf(
            "broken json",
            "null",
            valid.replace("\"version\":1", "\"version\":99"),
            valid.replace("\"questionIndex\":0", "\"questionIndex\":-1"),
            valid.replace("\"questionIndex\":0", "\"questionIndex\":10"),
            valid.replace("first", "removed"),
        )) {
            preferences.setValue(PostHogPreferences.SURVEY_PROGRESS, mapOf("survey/1" to invalid))
            assertNull(store.load(survey))
            assertEquals(emptyMap<String, Any>(), preferences.getValue(PostHogPreferences.SURVEY_PROGRESS))
        }
    }

    @Test
    fun `new iterations and ended or removed surveys clear old progress`() {
        val progress = SurveyProgress("submission", store.questionOrder(survey))
        store.save(survey, progress)
        val nextIteration = survey.copy(currentIteration = 2)
        assertNull(store.load(nextIteration))
        store.reconcile(listOf(nextIteration))
        assertNull(store.load(survey))
        store.save(survey, progress)
        store.reconcile(listOf(survey.copy(endDate = java.util.Date())))
        assertNull(store.load(survey))
        store.save(survey, progress)
        store.reconcile(emptyList())
        assertNull(store.load(survey))
    }
}
