package com.posthog.android.surveys

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.PostHogConfig
import com.posthog.PostHogInterface
import com.posthog.surveys.OnPostHogSurveyClosed
import com.posthog.surveys.OnPostHogSurveyResponse
import com.posthog.surveys.OnPostHogSurveyShown
import com.posthog.surveys.PostHogDisplaySurvey
import com.posthog.surveys.PostHogSurveysDelegate
import com.posthog.surveys.Survey
import com.posthog.surveys.SurveyConditions
import com.posthog.surveys.SurveyEventCondition
import com.posthog.surveys.SurveyEventConditions
import com.posthog.surveys.SurveyType
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the manual displaySurvey() API, which displays a survey by ID on demand,
 * bypassing display conditions (event triggers, seen checks) — the mobile counterpart
 * of the web SDK's posthog.displaySurvey().
 */
@RunWith(AndroidJUnit4::class)
internal class PostHogSurveysDisplaySurveyTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /**
     * A delegate that records rendered survey IDs and reports each survey as shown,
     * so the integration tracks it as the active survey (like the real UI delegates do).
     */
    private class RecordingDelegate : PostHogSurveysDelegate {
        val renderedSurveyIds = mutableListOf<String>()

        override fun renderSurvey(
            survey: PostHogDisplaySurvey,
            onSurveyShown: OnPostHogSurveyShown,
            onSurveyResponse: OnPostHogSurveyResponse,
            onSurveyClosed: OnPostHogSurveyClosed,
        ) {
            renderedSurveyIds.add(survey.id)
            onSurveyShown(survey)
        }

        override fun cleanupSurveys() {}
    }

    private fun createIntegration(
        delegate: RecordingDelegate,
        surveysEnabled: Boolean = true,
    ): PostHogSurveysIntegration {
        val config =
            PostHogConfig("test-api-key").apply {
                surveys = surveysEnabled
                surveysConfig.surveysDelegate = delegate
            }
        val integration = PostHogSurveysIntegration(context, config)
        val fake = mock<PostHogInterface>()
        whenever(fake.isFeatureEnabled(any(), any(), any())).thenReturn(true)
        integration.install(fake)
        return integration
    }

    private fun createSurvey(
        id: String,
        type: SurveyType,
        conditions: SurveyConditions? = null,
    ): Survey {
        return Survey(
            id = id,
            name = "Test Survey $id",
            type = type,
            questions = emptyList(),
            description = null,
            featureFlagKeys = null,
            linkedFlagKey = null,
            targetingFlagKey = null,
            internalTargetingFlagKey = null,
            conditions = conditions,
            appearance = null,
            currentIteration = null,
            currentIterationStartDate = null,
            startDate = java.util.Date(),
            endDate = null,
            schedule = null,
        )
    }

    private fun eventConditions(eventName: String): SurveyConditions {
        return SurveyConditions(
            url = null,
            urlMatchType = null,
            selector = null,
            deviceTypes = null,
            deviceTypesMatchType = null,
            seenSurveyWaitPeriodInDays = null,
            events = SurveyEventConditions(repeatedActivation = null, values = listOf(SurveyEventCondition(name = eventName))),
        )
    }

    @Test
    fun `displaySurvey renders an API-type survey that is never auto-displayed`() {
        val delegate = RecordingDelegate()
        val integration = createIntegration(delegate)
        integration.onSurveysLoaded(listOf(createSurvey("api-1", SurveyType.API)))
        assertFalse(delegate.renderedSurveyIds.contains("api-1"), "API survey should not be auto-displayed")

        integration.displaySurvey("api-1")

        assertTrue(delegate.renderedSurveyIds.contains("api-1"), "displaySurvey should render the API survey")
    }

    @Test
    fun `displaySurvey bypasses event trigger conditions`() {
        val delegate = RecordingDelegate()
        val integration = createIntegration(delegate)
        val survey = createSurvey("popover-1", SurveyType.POPOVER, conditions = eventConditions("some_event"))
        integration.onSurveysLoaded(listOf(survey))
        assertFalse(
            delegate.renderedSurveyIds.contains("popover-1"),
            "Survey with an unfired event trigger should not be auto-displayed",
        )

        integration.displaySurvey("popover-1")

        assertTrue(
            delegate.renderedSurveyIds.contains("popover-1"),
            "displaySurvey should render the survey even though its event trigger never fired",
        )
    }

    @Test
    fun `displaySurvey does nothing when the survey ID is unknown`() {
        val delegate = RecordingDelegate()
        val integration = createIntegration(delegate)
        integration.onSurveysLoaded(listOf(createSurvey("api-1", SurveyType.API)))

        integration.displaySurvey("unknown-id")

        assertEquals(emptyList(), delegate.renderedSurveyIds)
    }

    @Test
    fun `displaySurvey is ignored while another survey is active`() {
        val delegate = RecordingDelegate()
        val integration = createIntegration(delegate)
        val surveys =
            listOf(
                createSurvey("popover-1", SurveyType.POPOVER),
                createSurvey("api-1", SurveyType.API),
            )
        integration.onSurveysLoaded(surveys)
        assertEquals(listOf("popover-1"), delegate.renderedSurveyIds)

        integration.displaySurvey("api-1")

        assertEquals(listOf("popover-1"), delegate.renderedSurveyIds)
    }

    @Test
    fun `displaySurvey does nothing when surveys are disabled in config`() {
        val delegate = RecordingDelegate()
        val integration = createIntegration(delegate, surveysEnabled = false)
        integration.onSurveysLoaded(listOf(createSurvey("api-1", SurveyType.API)))

        integration.displaySurvey("api-1")

        assertEquals(emptyList(), delegate.renderedSurveyIds)
    }
}
