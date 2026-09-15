package com.posthog.android.surveys

import android.content.Context
import com.posthog.PostHog
import com.posthog.PostHogConfig
import com.posthog.PostHogInterface
import com.posthog.android.FakeSharedPreferences
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.internal.PostHogSharedPreferences
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogNetworkStatus
import com.posthog.internal.PostHogPreferences
import com.posthog.internal.PostHogSerializer
import com.posthog.surveys.PostHogSurveyResponse
import com.posthog.surveys.Survey
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
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
            valid.replace("\"questionText\":{}", "\"questionText\":null"),
        )) {
            preferences.setValue(PostHogPreferences.SURVEY_PROGRESS, mapOf("survey/1" to invalid))
            assertNull(store.load(survey))
            assertEquals(emptyMap<String, Any>(), preferences.getValue(PostHogPreferences.SURVEY_PROGRESS))
        }
    }

    @Test
    fun `reconciliation while locked preserves durable progress after unlock`() {
        val disk = FakeSharedPreferences()
        var locked = false
        val context =
            mock<Context> {
                on { getSharedPreferences(any(), any()) } doAnswer {
                    if (locked) throw IllegalStateException("User is locked")
                    disk
                }
            }
        val androidConfig = PostHogAndroidConfig("progress-test")
        androidConfig.cachePreferences = PostHogSharedPreferences(context, androidConfig)
        val beforeRestart = SurveyProgressStore(androidConfig)
        beforeRestart.save(survey, SurveyProgress("saved", beforeRestart.questionOrder(survey)))
        locked = true
        androidConfig.cachePreferences = PostHogSharedPreferences(context, androidConfig)
        val afterRestart = SurveyProgressStore(androidConfig)

        afterRestart.reconcile(listOf(survey))
        locked = false

        assertEquals("saved", assertNotNull(afterRestart.load(survey)).submissionId)
    }

    @Test
    fun `reset during preference reads cannot restore old progress or erase a new attempt`() {
        for (operation in listOf("load", "save", "reconcile", "remove")) {
            for (createNewAttempt in listOf(false, true)) {
                assertResetDuringRead(operation, createNewAttempt)
            }
        }
    }

    private fun assertResetDuringRead(
        operation: String,
        createNewAttempt: Boolean,
    ) {
        val backing = PostHogMemoryPreferences()
        var resetOnRead = false
        lateinit var sdk: PostHogInterface
        lateinit var progressStore: SurveyProgressStore
        val preferences =
            object : PostHogPreferences by backing {
                override fun getValue(
                    key: String,
                    defaultValue: Any?,
                ): Any? {
                    val snapshot = backing.getValue(key, defaultValue)
                    if (key == PostHogPreferences.SURVEY_PROGRESS && resetOnRead) {
                        resetOnRead = false
                        sdk.reset()
                        if (createNewAttempt) {
                            progressStore.save(survey, SurveyProgress("new-user", progressStore.questionOrder(survey)))
                        }
                    }
                    return snapshot
                }
            }
        val http =
            MockWebServer().apply {
                repeat(3) { enqueue(MockResponse().setBody("{}")) }
            }
        val directory = java.nio.file.Files.createTempDirectory("survey-reset").toFile()
        val config =
            PostHogConfig("store-reset-$operation", http.url("/").toString()).apply {
                cachePreferences = preferences
                preloadFeatureFlags = false
                storagePrefix = java.io.File(directory, "events").absolutePath
                replayStoragePrefix = java.io.File(directory, "replay").absolutePath
                networkStatus =
                    object : PostHogNetworkStatus {
                        override fun isConnected(): Boolean = false
                    }
            }
        sdk = PostHog.with(config)
        progressStore = SurveyProgressStore(config)
        try {
            val oldProgress = SurveyProgress("old-user", progressStore.questionOrder(survey))
            progressStore.save(survey, oldProgress)
            resetOnRead = true

            when (operation) {
                "load" -> assertNull(progressStore.load(survey))
                "save" -> progressStore.save(survey, oldProgress)
                "reconcile" -> progressStore.reconcile(listOf(survey))
                "remove" -> progressStore.remove(survey)
            }

            if (createNewAttempt) {
                assertEquals("new-user", assertNotNull(progressStore.load(survey)).submissionId)
            } else {
                assertNull(progressStore.load(survey))
            }
        } finally {
            sdk.close()
            http.shutdown()
            directory.deleteRecursively()
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
