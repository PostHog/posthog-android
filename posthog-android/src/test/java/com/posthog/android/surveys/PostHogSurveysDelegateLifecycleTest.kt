package com.posthog.android.surveys

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.PostHogConfig
import com.posthog.PostHogFake
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogSerializer
import com.posthog.surveys.OnPostHogSurveyClosed
import com.posthog.surveys.OnPostHogSurveyResponse
import com.posthog.surveys.OnPostHogSurveyShown
import com.posthog.surveys.PostHogDisplaySurvey
import com.posthog.surveys.PostHogSurveysDefaultDelegate
import com.posthog.surveys.PostHogSurveysDelegate
import com.posthog.surveys.Survey
import org.junit.runner.RunWith
import java.io.StringReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(AndroidJUnit4::class)
internal class PostHogSurveysDelegateLifecycleTest {
    private val config = PostHogConfig("delegate-lifecycle").apply { cachePreferences = PostHogMemoryPreferences() }
    private val integration = PostHogSurveysIntegration(ApplicationProvider.getApplicationContext(), config)
    private val survey =
        checkNotNull(
            PostHogSerializer(
                config,
            ).deserialize<Survey>(StringReader("""{"id":"delegate-test","name":"Test","type":"api","questions":[]}""")),
        )

    @Test
    fun `retired integration cleans up and cannot render again`() {
        var rendered = 0
        var cleaned = 0
        config.surveysConfig.surveysDelegate =
            object : PostHogSurveysDelegate by PostHogSurveysDefaultDelegate() {
                override fun renderSurvey(
                    survey: PostHogDisplaySurvey,
                    onSurveyShown: OnPostHogSurveyShown,
                    onSurveyResponse: OnPostHogSurveyResponse,
                    onSurveyClosed: OnPostHogSurveyClosed,
                ) {
                    rendered++
                }

                override fun cleanupSurveys() {
                    cleaned++
                }
            }
        integration.install(PostHogFake())
        integration.showSurvey(survey)
        assertEquals(1, rendered)
        integration.uninstall()
        assertEquals(1, cleaned)
        integration.showSurvey(survey)
        assertEquals(1, rendered)
    }

    @Test
    fun `custom render can wait for another thread to uninstall the integration`() {
        var completed: Boolean? = null
        var handoff: Thread? = null
        config.surveysConfig.surveysDelegate =
            object : PostHogSurveysDelegate by PostHogSurveysDefaultDelegate() {
                override fun renderSurvey(
                    survey: PostHogDisplaySurvey,
                    onSurveyShown: OnPostHogSurveyShown,
                    onSurveyResponse: OnPostHogSurveyResponse,
                    onSurveyClosed: OnPostHogSurveyClosed,
                ) {
                    val uninstalled = CountDownLatch(1)
                    handoff =
                        Thread {
                            integration.uninstall()
                            uninstalled.countDown()
                        }.apply { start() }
                    completed = uninstalled.await(2, TimeUnit.SECONDS)
                }
            }
        integration.install(PostHogFake())
        try {
            integration.showSurvey(survey)
            assertEquals(true, completed, "Custom rendering must not deadlock with an uninstall handoff")
        } finally {
            handoff?.join(2_000)
            assertFalse(handoff?.isAlive == true)
            integration.uninstall()
        }
    }

    @Test
    fun `queued reset cleanup preserves a new presentation before it reports shown`() {
        var rendered = 0
        var cleaned = 0
        config.surveysConfig.surveysDelegate =
            object : PostHogSurveysDelegate by PostHogSurveysDefaultDelegate() {
                override fun renderSurvey(
                    survey: PostHogDisplaySurvey,
                    onSurveyShown: OnPostHogSurveyShown,
                    onSurveyResponse: OnPostHogSurveyResponse,
                    onSurveyClosed: OnPostHogSurveyClosed,
                ) {
                    rendered++
                }

                override fun cleanupSurveys() {
                    cleaned++
                }
            }
        integration.install(PostHogFake())
        try {
            integration.showSurvey(survey)
            integration.onReset()
            integration.showSurvey(survey)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(2, rendered)
            assertEquals(0, cleaned)
        } finally {
            integration.uninstall()
        }
    }
}
