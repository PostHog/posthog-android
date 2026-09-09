package com.posthog.android.surveys.compose.internal

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.posthog.PostHog
import com.posthog.PostHogConfig
import com.posthog.android.surveys.PostHogSurveysIntegration
import com.posthog.android.surveys.compose.PostHogSurveysComposeDelegate
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.surveys.PostHogDisplayOpenQuestion
import com.posthog.surveys.PostHogDisplaySurvey
import com.posthog.surveys.PostHogDisplaySurveyAppearance
import com.posthog.surveys.PostHogDisplaySurveyTextContentType
import com.posthog.surveys.PostHogNextSurveyQuestion
import com.posthog.surveys.PostHogSurveyPresentation
import com.posthog.surveys.PostHogSurveyPresentationSession
import com.posthog.surveys.PostHogSurveyResponse
import com.posthog.surveys.PostHogSurveysConfig
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
internal class PostHogSurveyHostTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `finishing host preserves question and closes only on explicit dismiss`() {
        assertHostTransition(replacementAlreadyResumed = false)
    }

    @Test
    fun `finishing host resumes on an already resumed replacement activity`() {
        assertHostTransition(replacementAlreadyResumed = true)
    }

    @Test
    fun `reset removes unsent text before a new host resumes`() {
        assertResetBeforeHostTransition(resetAfterFinish = false)
    }

    @Test
    fun `reset discards retained unsent text after host has already finished`() {
        assertResetBeforeHostTransition(resetAfterFinish = true)
    }

    private fun assertResetBeforeHostTransition(resetAfterFinish: Boolean) {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val config = resetConfig()
        val sdk = PostHog.with(config)
        val integration = PostHogSurveysIntegration(application, config)
        integration.install(sdk)
        val delegate = config.surveysConfig.surveysDelegate
        val survey =
            PostHogDisplaySurvey(
                "reset",
                "Reset",
                listOf(PostHogDisplayOpenQuestion("q", "Private question?", null, PostHogDisplaySurveyTextContentType.TEXT, false, "Send")),
            )
        var closed = 0
        val oldAnswers = mutableListOf<PostHogSurveyResponse>()
        val freshAnswers = mutableListOf<PostHogSurveyResponse>()
        var replacement: ActivityScenario<ComponentActivity>? = null
        try {
            compose.activityRule.scenario.recreate()
            compose.runOnIdle {
                delegate.renderSurvey(survey, {}, { _, _, answer ->
                    oldAnswers.add(answer)
                    null
                }, { closed++ })
            }
            compose.onNode(hasSetTextAction()).performTextInput("Previous user secret")
            val oldClose =
                compose.onNodeWithContentDescription(
                    "Close survey",
                ).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            val oldSubmit = compose.onNodeWithText("Send").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            val distinctId = sdk.distinctId()
            if (!resetAfterFinish) {
                compose.runOnIdle { sdk.reset() }
                compose.onNodeWithText("Previous user secret").assertDoesNotExist()
                compose.onNodeWithText("Private question?").assertDoesNotExist()
            }
            compose.activityRule.scenario.close()
            if (resetAfterFinish) compose.runOnUiThread { sdk.reset() }
            if (replacement == null) replacement = ActivityScenario.launch(ComponentActivity::class.java)
            assertEquals(distinctId, sdk.distinctId())
            compose.onNodeWithText("Previous user secret").assertDoesNotExist()
            compose.onNodeWithText("Private question?").assertDoesNotExist()
            assertEquals(0, closed)
            compose.runOnIdle {
                delegate.renderSurvey(survey, {}, { _, _, answer ->
                    freshAnswers.add(answer)
                    null
                }, { closed++ })
            }
            compose.onNodeWithText("Private question?").assertIsDisplayed()
            compose.runOnIdle {
                oldClose()
                oldSubmit()
            }
            assertEquals(emptyList(), oldAnswers)
            assertEquals(emptyList(), freshAnswers)
            compose.onNodeWithText("Private question?").assertIsDisplayed()
            compose.onNodeWithText("Previous user secret").assertDoesNotExist()
            compose.onNodeWithContentDescription("Close survey").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitForIdle()
            assertEquals(1, closed)
        } finally {
            compose.runOnUiThread { delegate.cleanupSurveys() }
            replacement?.close()
            integration.uninstall()
            sdk.close()
        }
    }

    @Test
    fun `reset cancels queued and delayed shows while an older notification preserves fresh UI`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val delegate = PostHogSurveysComposeDelegate(application)
        val owner = PostHogSurveyPresentationSession(PostHogSurveysConfig())
        delegate.bindSurveySession(owner)
        val survey =
            PostHogDisplaySurvey(
                "pending",
                "Pending",
                listOf(PostHogDisplayOpenQuestion("q", "Fresh question?", null, PostHogDisplaySurveyTextContentType.TEXT, false, "Send")),
            )
        var shown = 0
        var closed = 0
        try {
            compose.activityRule.scenario.recreate()
            compose.runOnIdle {
                // Enqueue a show from the SDK thread, then invalidate it before main executes it.
                Thread {
                    delegate.renderSurvey(
                        PostHogSurveyPresentation(survey, 0, owner),
                        { shown++ },
                        { _, _, _ -> null },
                        { closed++ },
                    )
                }.apply {
                    start()
                    join(2_000)
                    assertFalse(isAlive, "Queued render must finish without waiting for main")
                }
                delegate.onSurveyReset(1, owner.config)
            }
            compose.onNodeWithText("Fresh question?").assertDoesNotExist()
            assertEquals(0, shown)
            compose.runOnIdle {
                delegate.renderSurvey(
                    PostHogSurveyPresentation(
                        survey.copy(appearance = PostHogDisplaySurveyAppearance(surveyPopupDelaySeconds = 2.0)),
                        1,
                        owner,
                    ),
                    {
                        shown++
                    },
                    { _, _, _ -> null },
                    { closed++ },
                )
                delegate.onSurveyReset(2, owner.config)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            }
            compose.onNodeWithText("Fresh question?").assertDoesNotExist()
            assertEquals(0, shown)
            assertFreshPresentationSurvivesStaleWork(delegate, survey, owner)
        } finally {
            compose.runOnUiThread { delegate.cleanupSurveys() }
        }
    }

    private fun assertFreshPresentationSurvivesStaleWork(
        delegate: PostHogSurveysComposeDelegate,
        survey: PostHogDisplaySurvey,
        owner: PostHogSurveyPresentationSession,
    ) {
        var shown = 0
        var closed = 0
        compose.runOnIdle {
            // Cleanup is queued, but a fresh presentation reaches main first.
            Thread { delegate.onSurveyReset(3, owner.config) }.apply {
                start()
                join(2_000)
                assertFalse(isAlive, "Reset notification must finish without waiting for main")
            }
            delegate.renderSurvey(PostHogSurveyPresentation(survey, 4, owner), { shown++ }, { _, _, _ -> null }, { closed++ })
            delegate.onSurveyReset(2, owner.config)
            delegate.renderSurvey(
                PostHogSurveyPresentation(survey.copy(questions = emptyList()), 3, owner),
                {},
                { _, _, _ -> null },
                {},
            )
        }
        compose.onNodeWithText("Fresh question?").assertIsDisplayed()
        val newOwner = PostHogSurveyPresentationSession(PostHogSurveysConfig())
        compose.runOnIdle {
            owner.invalidate()
            delegate.cleanupSurveys()
            delegate.bindSurveySession(newOwner)
            delegate.renderSurvey(PostHogSurveyPresentation(survey, 0, newOwner), { shown++ }, { _, _, _ -> null }, { closed++ })
            delegate.bindSurveySession(owner)
            delegate.cleanupSurveys(owner)
            delegate.onSurveyReset(10, owner.config)
            delegate.renderSurvey(
                PostHogSurveyPresentation(survey.copy(questions = emptyList()), 10, owner),
                {},
                { _, _, _ -> null },
                {},
            )
        }
        compose.onNodeWithText("Fresh question?").assertIsDisplayed()
        assertEquals(2, shown)
        assertEquals(0, closed)
        compose.onNodeWithContentDescription("Close survey").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        assertEquals(1, closed)
    }

    private fun assertHostTransition(replacementAlreadyResumed: Boolean) {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val provider = ActivityProvider()
        val host = PostHogSurveyHost(provider)
        var shown = 0
        var closed = 0
        val submitted = mutableListOf<Pair<Int, PostHogSurveyResponse>>()
        val survey =
            PostHogDisplaySurvey(
                id = "resume",
                name = "Resume",
                questions =
                    listOf("First?", "Second?").mapIndexed { index, text ->
                        PostHogDisplayOpenQuestion(index.toString(), text, null, PostHogDisplaySurveyTextContentType.TEXT, false, "Next")
                    },
            )
        application.registerActivityLifecycleCallbacks(provider)
        var replacement: ActivityScenario<ComponentActivity>? = null
        try {
            compose.runOnIdle {
                provider.onActivityResumed(compose.activity)
                host.show(survey, { shown++ }, { _, index, answer ->
                    submitted.add(index to answer)
                    PostHogNextSurveyQuestion(1, false)
                }, { closed++ })
            }
            compose.onNodeWithText("First?").assertIsDisplayed()
            compose.onNode(hasSetTextAction()).performTextInput("Saved")
            compose.onNodeWithText("Next").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
            assertEquals(listOf<Pair<Int, PostHogSurveyResponse>>(0 to PostHogSurveyResponse.Text("Saved")), submitted)
            compose.onNodeWithText("Second?").assertExists()
            compose.onNode(hasSetTextAction()).performTextInput("Unsent draft")

            if (replacementAlreadyResumed) replacement = ActivityScenario.launch(ComponentActivity::class.java)
            compose.activityRule.scenario.close()
            assertEquals(0, closed)
            if (replacement == null) replacement = ActivityScenario.launch(ComponentActivity::class.java)

            compose.onNodeWithText("Second?").assertExists()
            assertEquals(listOf<Pair<Int, PostHogSurveyResponse>>(0 to PostHogSurveyResponse.Text("Saved")), submitted)
            assertEquals(1, shown)
            compose.onNodeWithText("Unsent draft").assertIsDisplayed()
            compose.onNodeWithText("Next").performSemanticsAction(SemanticsActions.OnClick) { it() }
            assertEquals(
                listOf<Pair<Int, PostHogSurveyResponse>>(
                    0 to PostHogSurveyResponse.Text("Saved"),
                    1 to PostHogSurveyResponse.Text("Unsent draft"),
                ),
                submitted,
            )
            compose.onNodeWithContentDescription("Close survey").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitForIdle()
            assertEquals(1, closed)
        } finally {
            compose.runOnUiThread { host.cleanup() }
            replacement?.close()
            application.unregisterActivityLifecycleCallbacks(provider)
        }
    }

    @Suppress("DEPRECATION")
    private fun resetConfig(): PostHogConfig =
        PostHogConfig("host-reset", "http://127.0.0.1:1").apply {
            cachePreferences = PostHogMemoryPreferences()
            preloadFeatureFlags = false
            remoteConfig = false
            reuseAnonymousId = true
        }
}
