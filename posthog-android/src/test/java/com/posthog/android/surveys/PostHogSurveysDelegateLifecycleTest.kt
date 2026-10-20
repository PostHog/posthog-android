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

    @Test
    fun `survey awaiting shown is not rendered again`() {
        val shown = mutableListOf<OnPostHogSurveyShown>()
        val closed = mutableListOf<OnPostHogSurveyClosed>()
        config.surveysConfig.surveysDelegate = recordingDelegate(shown, closed)
        integration.install(PostHogFake())
        try {
            integration.showSurvey(survey)
            idleMainLooper()
            integration.showSurvey(survey)
            idleMainLooper()
            assertEquals(1, shown.size)

            shown.single()(displaySurvey())
            closed.single()(displaySurvey())
            integration.showSurvey(survey)
            idleMainLooper()
            assertEquals(2, shown.size)
        } finally {
            integration.uninstall()
        }
    }

    @Test
    fun `closing a survey before it shows releases it`() {
        val shown = mutableListOf<OnPostHogSurveyShown>()
        val closed = mutableListOf<OnPostHogSurveyClosed>()
        config.surveysConfig.surveysDelegate = recordingDelegate(shown, closed)
        integration.install(PostHogFake())
        try {
            integration.showSurvey(survey)
            idleMainLooper()
            closed.single()(displaySurvey())
            integration.showSurvey(survey)
            idleMainLooper()
            assertEquals(2, shown.size)
        } finally {
            integration.uninstall()
        }
    }

    private fun recordingDelegate(
        shown: MutableList<OnPostHogSurveyShown>,
        closed: MutableList<OnPostHogSurveyClosed>,
    ): PostHogSurveysDelegate =
        object : PostHogSurveysDelegate by PostHogSurveysDefaultDelegate() {
            override fun renderSurvey(
                survey: PostHogDisplaySurvey,
                onSurveyShown: OnPostHogSurveyShown,
                onSurveyResponse: OnPostHogSurveyResponse,
                onSurveyClosed: OnPostHogSurveyClosed,
            ) {
                shown += onSurveyShown
                closed += onSurveyClosed
            }
        }

    private fun displaySurvey() = PostHogDisplaySurvey.toDisplaySurvey(survey)

    private fun idleMainLooper() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
}
