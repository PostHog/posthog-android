package com.posthog.android.surveys.compose.internal

import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
internal class SurveyCompositionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `initial composition linkage failure closes only the survey`() {
        assertFailureIsIsolated(failInitially = true)
    }

    @Test
    fun `recomposition linkage failure closes only the survey`() {
        assertFailureIsIsolated(failInitially = false)
    }

    private fun assertFailureIsIsolated(failInitially: Boolean) {
        val fail = mutableStateOf(failInitially)
        withSurvey(
            content = {
                if (fail.value) throw NoSuchMethodError("Missing Material3 constructor")
                Text("Survey")
            },
        ) { survey ->
            if (!failInitially) {
                compose.onNodeWithText("Survey").assertIsDisplayed()
                compose.runOnIdle { fail.value = true }
            }
            compose.waitForIdle()
            assertIs<NoSuchMethodError>(survey.failures.single())
            assertFalse(survey.dialog.isShowing)
            compose.onNodeWithText("Survey").assertDoesNotExist()
        }
    }

    @Test
    fun `effect linkage failure closes only the survey`() {
        withSurvey(
            content = {
                Text("Survey")
                LaunchedEffect(Unit) { throw NoClassDefFoundError("Missing Material3 class") }
            },
        ) { survey ->
            compose.waitForIdle()
            assertIs<NoClassDefFoundError>(survey.failures.single())
            assertFalse(survey.dialog.isShowing)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun `nested bottom sheet composition linkage failure closes only the survey`() {
        withSurvey(
            content = {
                ModalBottomSheet(onDismissRequest = {}) {
                    DeferredSurveyContent {
                        throw NoSuchMethodError("Missing Material3 body API")
                    }
                }
            },
        ) { survey ->
            compose.waitForIdle()
            assertIs<NoSuchMethodError>(survey.failures.single())
            assertFalse(survey.dialog.isShowing)
        }
    }

    @Test
    fun `disposing before attachment never runs survey content or reports failure`() {
        var composed = false
        val failures = mutableListOf<Throwable>()
        compose.runOnIdle {
            val dialog = ComponentDialog(compose.activity)
            val survey = SurveyComposition(compose.activity, dialog.lifecycle) { failures.add(it) }
            survey.setContent {
                composed = true
                throw NoSuchMethodError("Disposed survey must not compose")
            }
            survey.dispose()
            try {
                dialog.setContentView(survey.view)
                dialog.show()
            } finally {
                dialog.dismiss()
            }
        }
        compose.waitForIdle()
        assertFalse(composed)
        assertEquals(emptyList(), failures)
    }

    private fun withSurvey(
        content: @Composable () -> Unit,
        block: (SurveyFixture) -> Unit,
    ) {
        val hostText = mutableStateOf("Host app")
        compose.setContent { Text(hostText.value) }
        lateinit var survey: SurveyFixture
        compose.runOnIdle { survey = SurveyFixture(content) }
        try {
            block(survey)
            compose.onAllNodes(isRoot()).assertCountEquals(1)
            compose.runOnIdle { hostText.value = "Host still works" }
            compose.onNodeWithText("Host still works").assertIsDisplayed()
            lateinit var freshSurvey: SurveyFixture
            compose.runOnIdle { freshSurvey = SurveyFixture { Text("Fresh survey") } }
            try {
                compose.onNodeWithText("Fresh survey").assertIsDisplayed()
                assertEquals(emptyList(), freshSurvey.failures)
            } finally {
                compose.runOnUiThread { freshSurvey.close() }
            }
        } finally {
            compose.runOnUiThread { survey.close() }
        }
    }

    private inner class SurveyFixture(content: @Composable () -> Unit) {
        val failures = mutableListOf<Throwable>()
        val dialog = ComponentDialog(compose.activity)
        val composition =
            SurveyComposition(compose.activity, dialog.lifecycle) { error ->
                failures.add(error)
                close()
            }

        init {
            composition.setContent(content)
            dialog.setContentView(composition.view)
            dialog.show()
        }

        fun close() {
            composition.dispose()
            dialog.dismiss()
        }
    }
}
