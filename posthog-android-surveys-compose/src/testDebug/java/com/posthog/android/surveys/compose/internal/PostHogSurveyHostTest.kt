package com.posthog.android.surveys.compose.internal

import android.app.Application
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
import com.posthog.surveys.PostHogDisplayOpenQuestion
import com.posthog.surveys.PostHogDisplaySurvey
import com.posthog.surveys.PostHogDisplaySurveyTextContentType
import com.posthog.surveys.PostHogNextSurveyQuestion
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

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

    private fun assertHostTransition(replacementAlreadyResumed: Boolean) {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val provider = ActivityProvider()
        val host = PostHogSurveyHost(provider)
        var shown = 0
        var closed = 0
        val submitted = mutableListOf<Int>()
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
                host.show(survey, { shown++ }, { _, index, _ ->
                    submitted.add(index)
                    PostHogNextSurveyQuestion(index + 1, false)
                }, { closed++ })
            }
            compose.onNodeWithText("First?").assertIsDisplayed()
            compose.onNode(hasSetTextAction()).performTextInput("Saved")
            compose.onNodeWithText("Next").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
            assertEquals(listOf(0), submitted)
            compose.onNodeWithText("Second?").assertExists()

            if (replacementAlreadyResumed) replacement = ActivityScenario.launch(ComponentActivity::class.java)
            compose.activityRule.scenario.close()
            assertEquals(0, closed)
            if (replacement == null) replacement = ActivityScenario.launch(ComponentActivity::class.java)

            compose.onNodeWithText("Second?").assertExists()
            assertEquals(listOf(0), submitted)
            assertEquals(1, shown)
            compose.onNodeWithContentDescription("Close survey").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitForIdle()
            assertEquals(1, closed)
        } finally {
            compose.runOnUiThread { host.cleanup() }
            replacement?.close()
            application.unregisterActivityLifecycleCallbacks(provider)
        }
    }
}
