package com.posthog.android.surveys.compose.internal

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.test.core.app.ActivityScenario
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
internal class SurveyComposeContainerTest {
    @Test
    fun `a linkage error in the first composition is reported instead of thrown`() {
        val failures = mutableListOf<Throwable>()
        var composed = 0
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContentView(
                    SurveyComposeContainer(
                        context = activity,
                        content = {
                            composed++
                            throw NoSuchMethodError("incompatible Compose")
                        },
                        onFailure = { failures.add(it) },
                    ),
                )
            }
            shadowOf(Looper.getMainLooper()).idle()
            scenario.onActivity { it.window.decorView.requestLayout() }
            shadowOf(Looper.getMainLooper()).idle()
        }

        assertEquals(1, failures.size)
        assertIs<NoSuchMethodError>(failures.single())
        assertEquals(1, composed)
    }

    @Test
    fun `content composes when it does not throw`() {
        val failures = mutableListOf<Throwable>()
        var composed = false
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContentView(
                    SurveyComposeContainer(
                        context = activity,
                        content = {
                            composed = true
                            Text("Survey")
                        },
                        onFailure = { failures.add(it) },
                    ),
                )
            }
            shadowOf(Looper.getMainLooper()).idle()
        }

        assertTrue(composed)
        assertEquals(emptyList(), failures)
    }
}
