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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
        var boundConfig: PostHogSurveysConfig? = null
        val delegate =
            object : PostHogSurveysResetAwareDelegate, PostHogSurveysDelegate by PostHogSurveysDefaultDelegate() {
                override fun renderSurvey(
                    presentation: PostHogSurveyPresentation,
                    onSurveyShown: OnPostHogSurveyShown,
                    onSurveyResponse: OnPostHogSurveyResponse,
                    onSurveyClosed: OnPostHogSurveyClosed,
                ) {
                    generation = presentation.resetGeneration
                }

                override fun bindSurveySession(session: PostHogSurveyPresentationSession) {
                    boundConfig = session.config
                }

                override fun cleanupSurveys(session: PostHogSurveyPresentationSession) = Unit

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
            assertEquals(config.surveysConfig, boundConfig)
            integration.uninstall()
            generation = null
            boundConfig = null
            integration.showSurvey(survey)
            assertNull(generation)
            assertNull(boundConfig)
        } finally {
            integration.uninstall()
        }
    }

    @Test
    fun `custom render runs without the integration lifecycle monitor`() {
        var heldLock: Boolean? = null
        config.surveysConfig.surveysDelegate =
            object : PostHogSurveysDelegate by PostHogSurveysDefaultDelegate() {
                override fun renderSurvey(
                    survey: PostHogDisplaySurvey,
                    onSurveyShown: OnPostHogSurveyShown,
                    onSurveyResponse: OnPostHogSurveyResponse,
                    onSurveyClosed: OnPostHogSurveyClosed,
                ) {
                    val field = PostHogSurveysIntegration::class.java.getDeclaredField("lifecycleLock").apply { isAccessible = true }
                    heldLock = Thread.holdsLock(checkNotNull(field.get(integration)))
                }
            }
        integration.install(PostHogFake())
        try {
            integration.showSurvey(survey)
            assertEquals(false, heldLock)
        } finally {
            integration.uninstall()
        }
    }
}
