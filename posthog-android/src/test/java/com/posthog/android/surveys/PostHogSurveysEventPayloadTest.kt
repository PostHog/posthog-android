package com.posthog.android.surveys

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.PostHog
import com.posthog.PostHogBeforeSend
import com.posthog.PostHogConfig
import com.posthog.PostHogFake
import com.posthog.PostHogInterface
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.internal.PostHogSharedPreferences
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogNetworkStatus
import com.posthog.internal.PostHogPreferences
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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
internal class PostHogSurveysEventPayloadTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val serializer = PostHogSerializer(PostHogConfig("test-api-key"))

    private class RecordingDelegate : PostHogSurveysDelegate {
        var shownSurvey: PostHogDisplaySurvey? = null
        var onSurveyShown: OnPostHogSurveyShown? = null
        var onSurveyResponse: OnPostHogSurveyResponse? = null
        var onSurveyClosed: OnPostHogSurveyClosed? = null
        var cleanupCalls = 0

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

        override fun cleanupSurveys() {
            cleanupCalls++
        }
    }

    private fun createIntegration(
        delegate: RecordingDelegate,
        preferences: PostHogPreferences = PostHogMemoryPreferences(),
    ): Pair<PostHogSurveysIntegration, PostHogFake> {
        val config =
            PostHogConfig("test-api-key").apply {
                cachePreferences = preferences
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

    private fun partialResponseSurvey(
        enabled: Boolean?,
        endAfterFirst: Boolean = false,
    ): Survey {
        val questions =
            listOf(
                mapOf(
                    "id" to "first",
                    "type" to "open",
                    "question" to "First?",
                    "optional" to true,
                    "branching" to if (endAfterFirst) mapOf("type" to "end") else null,
                ),
                mapOf("id" to "second", "type" to "open", "question" to "Second?"),
            )
        return assertNotNull(
            serializer.deserializeList<Survey>(
                listOf(
                    mapOf(
                        "id" to "partial-survey",
                        "name" to "Partial survey",
                        "type" to "popover",
                        "questions" to questions,
                        "enable_partial_responses" to enabled,
                    ),
                ),
            )?.firstOrNull(),
        )
    }

    @Test
    fun `unfinished responses survive integration restart`() {
        for (enabled in listOf(true, false, null)) {
            val preferences = PostHogSharedPreferences(context, PostHogAndroidConfig("survey-resume-test"))
            preferences.clear()
            val delegate = RecordingDelegate()
            val survey = partialResponseSurvey(enabled)
            val (first, firstPostHog) = createIntegration(delegate, preferences)
            first.showSurvey(survey)
            val display = assertNotNull(delegate.shownSurvey)
            assertNotNull(delegate.onSurveyShown).invoke(display)
            assertNotNull(delegate.onSurveyResponse).invoke(display, 0, PostHogSurveyResponse.Text("Saved"))
            val submissionId = firstPostHog.properties?.get("\$survey_submission_id")
            first.uninstall()

            val reloadedPreferences = PostHogSharedPreferences(context, PostHogAndroidConfig("survey-resume-test"))
            val (resumed, resumedPostHog) = createIntegration(delegate, reloadedPreferences)
            try {
                resumed.showSurvey(survey)
                val restored = assertNotNull(delegate.shownSurvey)
                assertEquals(1, restored.initialQuestionIndex)
                assertNotNull(delegate.onSurveyShown).invoke(restored)
                assertNotNull(delegate.onSurveyResponse).invoke(restored, 1, PostHogSurveyResponse.Text("Final"))
                val properties = assertNotNull(resumedPostHog.properties)
                assertEquals("Saved", properties["\$survey_response_first"])
                assertEquals(true, properties["\$survey_completed"])
                if (enabled == true) assertEquals(submissionId, properties["\$survey_submission_id"])
                assertNotNull(delegate.onSurveyClosed).invoke(restored)
                resumed.showSurvey(survey)
                assertEquals(0, assertNotNull(delegate.shownSurvey).initialQuestionIndex)
            } finally {
                resumed.uninstall()
                preferences.clear()
            }
        }
    }

    @Test
    fun `reset before a restored survey is shown rejects captured answers`() {
        val preferences = PostHogMemoryPreferences()
        val delegate = RecordingDelegate()
        val survey = partialResponseSurvey(true)
        val (first, _) = createIntegration(delegate, preferences)
        first.showSurvey(survey)
        val firstDisplay = assertNotNull(delegate.shownSurvey)
        assertNotNull(delegate.onSurveyShown).invoke(firstDisplay)
        assertNotNull(delegate.onSurveyResponse).invoke(firstDisplay, 0, PostHogSurveyResponse.Text("Previous user"))
        first.uninstall()

        val (resumed, postHog) = createIntegration(delegate, preferences)
        try {
            resumed.showSurvey(survey)
            val restored = assertNotNull(delegate.shownSurvey)
            assertEquals(1, restored.initialQuestionIndex)
            preferences.clear()
            val count = postHog.captures
            assertNotNull(delegate.onSurveyShown).invoke(restored)
            assertNull(assertNotNull(delegate.onSurveyResponse).invoke(restored, 1, PostHogSurveyResponse.Text("Next user")))
            assertEquals(count, postHog.captures)
            assertNull(preferences.getValue(PostHogPreferences.SURVEY_PROGRESS))
        } finally {
            resumed.uninstall()
        }
    }

    @Test
    fun `stale shown callback does not clean up a newer survey`() {
        val preferences = PostHogMemoryPreferences()
        val delegate = RecordingDelegate()
        val survey = partialResponseSurvey(true)
        val (first, _) = createIntegration(delegate, preferences)
        first.showSurvey(survey)
        val firstDisplay = assertNotNull(delegate.shownSurvey)
        assertNotNull(delegate.onSurveyShown).invoke(firstDisplay)
        assertNotNull(delegate.onSurveyResponse).invoke(firstDisplay, 0, PostHogSurveyResponse.Text("Saved"))
        first.uninstall()
        val (resumed, postHog) = createIntegration(delegate, preferences)
        try {
            resumed.showSurvey(survey)
            val staleDisplay = assertNotNull(delegate.shownSurvey)
            val staleShown = assertNotNull(delegate.onSurveyShown)
            preferences.clear()
            resumed.showSurvey(survey.copy(id = "new-survey"))
            val currentDisplay = assertNotNull(delegate.shownSurvey)
            assertNotNull(delegate.onSurveyShown).invoke(currentDisplay)
            val captures = postHog.captures
            val cleanupCalls = delegate.cleanupCalls

            staleShown(staleDisplay)

            assertEquals(cleanupCalls, delegate.cleanupCalls)
            assertEquals(captures, postHog.captures)
            assertNotNull(assertNotNull(delegate.onSurveyResponse).invoke(currentDisplay, 0, PostHogSurveyResponse.Text("Current")))
            assertEquals("new-survey", postHog.properties?.get("\$survey_id"))
        } finally {
            resumed.uninstall()
        }
    }

    @Test
    fun `capture callback can reset without retaining previous progress`() {
        val delegate = RecordingDelegate()
        val preferences = PostHogMemoryPreferences()
        val directory = java.io.File(context.cacheDir, java.util.UUID.randomUUID().toString()).apply { mkdirs() }
        lateinit var sdk: PostHogInterface
        var heldSurveyLock = true
        var sentIdentity: String? = null
        val http =
            MockWebServer().apply {
                enqueue(MockResponse().setBody("{}"))
                enqueue(MockResponse().setBody("{}"))
            }
        val config =
            PostHogConfig("reset-callback-test", http.url("/").toString()).apply {
                cachePreferences = preferences
                storagePrefix = java.io.File(directory, "events").absolutePath
                replayStoragePrefix = java.io.File(directory, "replay").absolutePath
                preloadFeatureFlags = false
                networkStatus =
                    object : PostHogNetworkStatus {
                        override fun isConnected(): Boolean = false
                    }
                surveys = true
                surveysConfig.surveysDelegate = delegate
                addBeforeSend(
                    PostHogBeforeSend { event ->
                        if (event.event == "survey sent") {
                            heldSurveyLock = Thread.holdsLock(surveysConfig)
                            sentIdentity = event.distinctId
                            sdk.reset()
                        }
                        null
                    },
                )
            }
        val integration = PostHogSurveysIntegration(context, config)
        config.addIntegration(integration)
        sdk = PostHog.with(config)
        try {
            val previousIdentity = sdk.distinctId()
            integration.showSurvey(partialResponseSurvey(true))
            val display = assertNotNull(delegate.shownSurvey)
            assertNotNull(delegate.onSurveyShown).invoke(display)
            assertNotNull(delegate.onSurveyResponse).invoke(display, 0, PostHogSurveyResponse.Text("Previous user"))

            assertFalse(heldSurveyLock)
            assertEquals(previousIdentity, sentIdentity)
            assertNotEquals(previousIdentity, sdk.distinctId())
            assertNull(preferences.getValue(PostHogPreferences.SURVEY_PROGRESS))
            assertNull(preferences.getValue(PostHogPreferences.SURVEY_SEEN))
            assertNull(assertNotNull(delegate.onSurveyResponse).invoke(display, 1, PostHogSurveyResponse.Text("Stale")))
        } finally {
            sdk.close()
            http.shutdown()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `dismissal and reset clear saved progress without stale callbacks restoring it`() {
        for (reset in listOf(false, true)) {
            val preferences = PostHogMemoryPreferences()
            val delegate = RecordingDelegate()
            val (integration, postHog) = createIntegration(delegate, preferences)
            try {
                integration.showSurvey(partialResponseSurvey(true))
                val display = assertNotNull(delegate.shownSurvey)
                assertNotNull(delegate.onSurveyShown).invoke(display)
                assertNotNull(delegate.onSurveyResponse).invoke(display, 0, PostHogSurveyResponse.Text("Saved"))
                if (reset) {
                    preferences.clear()
                    val count = postHog.captures
                    assertNull(assertNotNull(delegate.onSurveyResponse).invoke(display, 1, PostHogSurveyResponse.Text("Stale")))
                    assertEquals(count, postHog.captures)
                } else {
                    assertNotNull(delegate.onSurveyClosed).invoke(display)
                }
                integration.showSurvey(partialResponseSurvey(true))
                assertEquals(0, assertNotNull(delegate.shownSurvey).initialQuestionIndex)
            } finally {
                integration.uninstall()
                preferences.clear()
            }
        }
    }

    @Test
    fun `unfinished surveys bypass seen and internal targeting but honor linked flags`() {
        val preferences = PostHogMemoryPreferences()
        val delegate = RecordingDelegate()
        val (integration, _) = createIntegration(delegate, preferences)
        val survey = partialResponseSurvey(true).copy(startDate = java.util.Date())
        integration.showSurvey(survey)
        val display = assertNotNull(delegate.shownSurvey)
        assertNotNull(delegate.onSurveyShown).invoke(display)
        assertNotNull(delegate.onSurveyResponse).invoke(display, 0, PostHogSurveyResponse.Text("Saved"))
        integration.uninstall()
        delegate.shownSurvey = null
        val (resumed, _) = createIntegration(delegate, preferences)
        try {
            resumed.onSurveysLoaded(listOf(survey.copy(internalTargetingFlagKey = "already-answered")))
            assertEquals(1, assertNotNull(delegate.shownSurvey).initialQuestionIndex)
            delegate.shownSurvey = null
            resumed.onSurveysLoaded(listOf(survey.copy(linkedFlagKey = "disabled-product-flag")))
            assertNull(delegate.shownSurvey)
        } finally {
            resumed.uninstall()
            preferences.clear()
        }
    }

    @Test
    fun `restart restores the branching destination and omits skipped answers`() {
        val preferences = PostHogMemoryPreferences()
        val delegate = RecordingDelegate()
        val questions =
            assertNotNull(
                serializer.deserializeList<SurveyQuestion>(
                    listOf(
                        mapOf(
                            "id" to "first",
                            "type" to "open",
                            "question" to "First?",
                            "branching" to mapOf("type" to "specific_question", "index" to 2),
                        ),
                        mapOf("id" to "skipped", "type" to "open", "question" to "Skipped?"),
                        mapOf("id" to "last", "type" to "open", "question" to "Last?"),
                    ),
                ),
            )
        val survey = partialResponseSurvey(true).copy(questions = questions)
        val (first, _) = createIntegration(delegate, preferences)
        first.showSurvey(survey)
        val display = assertNotNull(delegate.shownSurvey)
        assertNotNull(delegate.onSurveyShown).invoke(display)
        assertNotNull(delegate.onSurveyResponse).invoke(display, 0, PostHogSurveyResponse.Text("Saved"))
        first.uninstall()
        val (resumed, postHog) = createIntegration(delegate, preferences)
        try {
            resumed.showSurvey(survey)
            val restored = assertNotNull(delegate.shownSurvey)
            assertEquals(2, restored.initialQuestionIndex)
            assertNotNull(delegate.onSurveyShown).invoke(restored)
            assertNotNull(delegate.onSurveyResponse).invoke(restored, 2, PostHogSurveyResponse.Text("Final"))
            assertEquals("Saved", postHog.properties?.get("\$survey_response_first"))
            assertNull(postHog.properties?.get("\$survey_response_skipped"))
        } finally {
            resumed.uninstall()
            preferences.clear()
        }
    }

    @Test
    fun `showing a survey alone does not create resumable progress`() {
        val preferences = PostHogMemoryPreferences()
        val delegate = RecordingDelegate()
        val (integration, _) = createIntegration(delegate, preferences)
        try {
            integration.showSurvey(partialResponseSurvey(true))
            assertNotNull(delegate.onSurveyShown).invoke(assertNotNull(delegate.shownSurvey))
            assertNull(preferences.getValue(PostHogPreferences.SURVEY_PROGRESS))
        } finally {
            integration.uninstall()
            preferences.clear()
        }
    }

    @Test
    fun `partial responses emit cumulative answers with one submission id`() {
        for (enabled in listOf(true, false, null)) {
            val delegate = RecordingDelegate()
            val (integration, postHog) = createIntegration(delegate)
            try {
                integration.showSurvey(partialResponseSurvey(enabled))
                val survey = assertNotNull(delegate.shownSurvey)
                assertNotNull(delegate.onSurveyShown).invoke(survey)
                val respond = assertNotNull(delegate.onSurveyResponse)
                val first = assertNotNull(respond(survey, 0, PostHogSurveyResponse.Text("First answer")))
                assertEquals(false, first.isSurveyCompleted)
                assertEquals(if (enabled == true) 2 else 1, postHog.captures)
                val partial = postHog.properties
                if (enabled == true) {
                    assertEquals("survey sent", postHog.event)
                    assertEquals(false, partial?.get("\$survey_completed"))
                    assertEquals("First answer", partial?.get("\$survey_response_first"))
                    assertNull(partial?.get("\$survey_response_second"))
                }
                respond(survey, 1, PostHogSurveyResponse.Text("Second answer"))
                assertEquals(if (enabled == true) 3 else 2, postHog.captures)
                assertEquals("survey sent", postHog.event)
                val completed = assertNotNull(postHog.properties)
                assertEquals(true, completed["\$survey_completed"])
                assertEquals("First answer", completed["\$survey_response_first"])
                assertEquals("Second answer", completed["\$survey_response_second"])
                val submissionId = assertNotNull(completed["\$survey_submission_id"] as? String)
                java.util.UUID.fromString(submissionId)
                if (enabled == true) assertEquals(submissionId, partial?.get("\$survey_submission_id"))
                assertNotNull(delegate.onSurveyClosed).invoke(survey)
                assertEquals(if (enabled == true) 3 else 2, postHog.captures)
            } finally {
                integration.uninstall()
            }
        }
    }

    @Test
    fun `dismissal keeps submission id and a new attempt gets a new id`() {
        val delegate = RecordingDelegate()
        val (integration, postHog) = createIntegration(delegate)
        try {
            val original = partialResponseSurvey(true)
            integration.showSurvey(original)
            val survey = assertNotNull(delegate.shownSurvey)
            assertNotNull(delegate.onSurveyShown).invoke(survey)
            assertNotNull(delegate.onSurveyResponse).invoke(survey, 0, PostHogSurveyResponse.Text("Saved"))
            assertEquals("survey sent", postHog.event)
            val submissionId = assertNotNull(postHog.properties?.get("\$survey_submission_id"))
            assertNotNull(delegate.onSurveyClosed).invoke(survey)
            assertEquals("survey dismissed", postHog.event)
            assertEquals(submissionId, postHog.properties?.get("\$survey_submission_id"))
            assertEquals(true, postHog.properties?.get("\$survey_partially_completed"))
            assertEquals("Saved", postHog.properties?.get("\$survey_response_first"))
            integration.showSurvey(original)
            assertNotNull(delegate.onSurveyShown).invoke(assertNotNull(delegate.shownSurvey))
            assertNotNull(delegate.onSurveyResponse).invoke(assertNotNull(delegate.shownSurvey), 0, PostHogSurveyResponse.Text("New"))
            val nextId = assertNotNull(postHog.properties?.get("\$survey_submission_id"))
            kotlin.test.assertNotEquals(submissionId, nextId)
        } finally {
            integration.uninstall()
        }
    }

    @Test
    fun `branching to end completes a partial-enabled survey even with a skipped optional answer`() {
        val delegate = RecordingDelegate()
        val (integration, postHog) = createIntegration(delegate)
        try {
            integration.showSurvey(partialResponseSurvey(true, endAfterFirst = true))
            val survey = assertNotNull(delegate.shownSurvey)
            assertNotNull(delegate.onSurveyShown).invoke(survey)
            val next = assertNotNull(assertNotNull(delegate.onSurveyResponse).invoke(survey, 0, PostHogSurveyResponse.Text(null)))
            assertEquals(true, next.isSurveyCompleted)
            assertEquals(2, postHog.captures)
            assertEquals(true, postHog.properties?.get("\$survey_completed"))
            assertNull(postHog.properties?.get("\$survey_response_second"))
        } finally {
            integration.uninstall()
        }
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

    @Test
    fun `survey dismissed ignores null rating response`() {
        val delegate = RecordingDelegate()
        val (integration, postHog) = createIntegration(delegate)
        val survey = createSurvey(id = "null-rating-survey", name = "Null Rating Survey")

        integration.showSurvey(survey)

        val shownSurvey = assertNotNull(delegate.shownSurvey)
        assertNotNull(delegate.onSurveyShown).invoke(shownSurvey)
        assertNotNull(delegate.onSurveyResponse).invoke(shownSurvey, 0, PostHogSurveyResponse.Rating(null))
        assertNotNull(delegate.onSurveyClosed).invoke(shownSurvey)

        assertEquals("survey dismissed", postHog.event)

        val properties = assertNotNull(postHog.properties)
        assertEquals(false, properties["\$survey_partially_completed"])
        assertNull(properties["\$survey_response"])
        assertNull(properties["\$survey_response_question-1"])
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
    }
}
