package com.posthog.android.surveys.compose.internal

import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.Layout
import androidx.test.core.app.ActivityScenario
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Compose test rules intercept measurement failures before the Android view can catch them.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
internal class SurveyMeasurementTest {
    @Test
    fun `initial measurement linkage failure closes only the survey`() {
        assertFailureIsIsolated(failInitially = true)
    }

    @Test
    fun `remeasurement linkage failure closes only the survey`() {
        assertFailureIsIsolated(failInitially = false)
    }

    private fun assertFailureIsIsolated(failInitially: Boolean) {
        val fail = mutableStateOf(failInitially)
        var successfulMeasures = 0
        var failedMeasures = 0
        var freshMeasures = 0
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var hostText: TextView
            lateinit var survey: SurveyFixture
            var freshSurvey: SurveyFixture? = null
            try {
                scenario.onActivity { activity ->
                    hostText = TextView(activity).apply { text = "Host app" }
                    activity.setContentView(hostText)
                    survey =
                        SurveyFixture(activity) {
                            Layout { _, _ ->
                                if (fail.value) {
                                    failedMeasures++
                                    throw NoSuchMethodError("Missing measurement API")
                                }
                                successfulMeasures++
                                layout(100, 100) {}
                            }
                        }
                }
                shadowOf(Looper.getMainLooper()).idle()
                if (!failInitially) {
                    assertTrue(successfulMeasures > 0)
                    scenario.onActivity {
                        fail.value = true
                        survey.composition.view.requestLayout()
                    }
                    shadowOf(Looper.getMainLooper()).idle()
                }
                assertIs<NoSuchMethodError>(survey.failures.single())
                assertEquals(1, failedMeasures)
                assertFalse(survey.dialog.isShowing)
                scenario.onActivity {
                    val exact = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
                    survey.composition.view.measure(exact, exact)
                    survey.composition.view.layout(0, 0, 100, 100)
                    hostText.text = "Host still works"
                    freshSurvey =
                        SurveyFixture(it) {
                            Layout { _, _ ->
                                freshMeasures++
                                layout(100, 100) {}
                            }
                        }
                }
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(1, survey.failures.size)
                assertEquals(1, failedMeasures)
                assertEquals("Host still works", hostText.text.toString())
                assertTrue(freshSurvey!!.dialog.isShowing)
                assertTrue(freshMeasures > 0)
                assertEquals(emptyList(), freshSurvey!!.failures)
            } finally {
                scenario.onActivity {
                    survey.close()
                    freshSurvey?.close()
                }
            }
        }
    }

    private class SurveyFixture(
        activity: ComponentActivity,
        content: @Composable () -> Unit,
    ) {
        val failures = mutableListOf<Throwable>()
        val dialog = ComponentDialog(activity)
        val composition =
            SurveyComposition(activity, dialog.lifecycle) { error ->
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
