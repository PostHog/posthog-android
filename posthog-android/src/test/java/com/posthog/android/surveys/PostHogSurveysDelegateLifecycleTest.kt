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
import com.posthog.surveys.PostHogSurveyPresentation
import com.posthog.surveys.PostHogSurveyPresentationSession
import com.posthog.surveys.PostHogSurveysConfig
import com.posthog.surveys.PostHogSurveysDefaultDelegate
import com.posthog.surveys.PostHogSurveysDelegate
import com.posthog.surveys.PostHogSurveysResetAwareDelegate
import com.posthog.surveys.Survey
import org.junit.runner.RunWith
import java.io.StringReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
    fun `replacement delegate is bound and retired integration cannot render again`() {
        var generation: Long? = null
        var boundSession: PostHogSurveyPresentationSession? = null
        var renderedSession: PostHogSurveyPresentationSession? = null
        var cleanedSession: PostHogSurveyPresentationSession? = null
        val delegate =
            object : PostHogSurveysResetAwareDelegate, PostHogSurveysDelegate by PostHogSurveysDefaultDelegate() {
                override fun renderSurvey(
                    presentation: PostHogSurveyPresentation,
                    onSurveyShown: OnPostHogSurveyShown,
                    onSurveyResponse: OnPostHogSurveyResponse,
                    onSurveyClosed: OnPostHogSurveyClosed,
                ) {
                    generation = presentation.resetGeneration
                    renderedSession = presentation.session
                }

                override fun bindSurveySession(session: PostHogSurveyPresentationSession) {
                    boundSession = session
                }

                override fun cleanupSurveys(session: PostHogSurveyPresentationSession) {
                    cleanedSession = session
                }

                override fun onSurveyReset(
                    resetGeneration: Long,
                    config: PostHogSurveysConfig,
                ) = Unit
            }
        integration.install(PostHogFake())
        config.surveysConfig.surveysDelegate = delegate
        try {
            integration.showSurvey(survey)
            assertEquals(0L, generation)
            val firstSession = assertNotNull(boundSession)
            assertSame(config.surveysConfig, firstSession.config)
            assertSame(firstSession, renderedSession)
            assertTrue(firstSession.isActive)
            integration.uninstall()
            assertFalse(firstSession.isActive)
            assertSame(firstSession, cleanedSession)
            generation = null
            boundSession = null
            integration.showSurvey(survey)
            assertNull(generation)
            assertNull(boundSession)
            integration.install(PostHogFake())
            integration.showSurvey(survey)
            val nextSession = assertNotNull(boundSession)
            assertNotSame(firstSession, nextSession)
            assertTrue(nextSession.isActive)
            assertSame(nextSession, renderedSession)
        } finally {
            integration.uninstall()
        }
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
}
