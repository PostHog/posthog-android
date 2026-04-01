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
import com.posthog.surveys.SurveyType
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests that API-type surveys are excluded from auto-display but still returned
 * by getActiveMatchingSurveys().
 *
 * This matches the canonical pattern from the web SDK where:
 * - getActiveMatchingSurveys() returns all eligible surveys including API type
 * - The auto-display layer (showNextSurvey) filters to only popover and widget
 *
 * See: https://github.com/PostHog/posthog-android/issues/474
 */
@RunWith(AndroidJUnit4::class)
internal class PostHogSurveysAutoDisplayFilterTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /**
     * A delegate that records which survey IDs were passed to renderSurvey().
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
        }

        override fun cleanupSurveys() {}
    }

    private fun createIntegration(delegate: RecordingDelegate): PostHogSurveysIntegration {
        val config =
            PostHogConfig("test-api-key").apply {
                surveys = true
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
            conditions = null,
            appearance = null,
            currentIteration = null,
            currentIterationStartDate = null,
            startDate = java.util.Date(),
            endDate = null,
            schedule = null,
        )
    }

    @Test
    fun `getActiveMatchingSurveys includes API surveys`() {
        val delegate = RecordingDelegate()
        val integration = createIntegration(delegate)
        val apiSurvey = createSurvey("api-1", SurveyType.API)
        integration.onSurveysLoaded(listOf(apiSurvey))

        val result = integration.getActiveMatchingSurveys()

        assertTrue(result.any { it.id == "api-1" }, "API surveys should be returned by getActiveMatchingSurveys")
    }

    @Test
    fun `showNextSurvey does not render API surveys`() {
        val delegate = RecordingDelegate()
        val integration = createIntegration(delegate)
        val apiSurvey = createSurvey("api-1", SurveyType.API)
        integration.onSurveysLoaded(listOf(apiSurvey))

        // onSurveysLoaded triggers showNextSurvey internally, so check the delegate
        assertFalse(
            delegate.renderedSurveyIds.contains("api-1"),
            "API surveys should not be auto-displayed via showNextSurvey",
        )
    }

    @Test
    fun `showNextSurvey renders popover surveys`() {
        val delegate = RecordingDelegate()
        val integration = createIntegration(delegate)
        val popoverSurvey = createSurvey("popover-1", SurveyType.POPOVER)
        integration.onSurveysLoaded(listOf(popoverSurvey))

        assertTrue(
            delegate.renderedSurveyIds.contains("popover-1"),
            "Popover surveys should be auto-displayed",
        )
    }

    @Test
    fun `showNextSurvey renders widget surveys`() {
        val delegate = RecordingDelegate()
        val integration = createIntegration(delegate)
        val widgetSurvey = createSurvey("widget-1", SurveyType.WIDGET)
        integration.onSurveysLoaded(listOf(widgetSurvey))

        assertTrue(
            delegate.renderedSurveyIds.contains("widget-1"),
            "Widget surveys should be auto-displayed",
        )
    }

    @Test
    fun `showNextSurvey skips API and renders first displayable survey`() {
        val delegate = RecordingDelegate()
        val integration = createIntegration(delegate)
        val surveys =
            listOf(
                createSurvey("api-1", SurveyType.API),
                createSurvey("api-2", SurveyType.API),
                createSurvey("popover-1", SurveyType.POPOVER),
            )
        integration.onSurveysLoaded(surveys)

        assertFalse(delegate.renderedSurveyIds.contains("api-1"), "API survey should not be rendered")
        assertFalse(delegate.renderedSurveyIds.contains("api-2"), "API survey should not be rendered")
        assertTrue(delegate.renderedSurveyIds.contains("popover-1"), "Popover survey should be rendered")
    }

    @Test
    fun `getActiveMatchingSurveys returns all types for mixed surveys`() {
        val delegate = RecordingDelegate()
        val integration = createIntegration(delegate)
        val surveys =
            listOf(
                createSurvey("api-1", SurveyType.API),
                createSurvey("popover-1", SurveyType.POPOVER),
                createSurvey("widget-1", SurveyType.WIDGET),
            )
        integration.onSurveysLoaded(surveys)

        val result = integration.getActiveMatchingSurveys()

        assertTrue(result.any { it.id == "api-1" }, "API surveys should be in matching results")
        assertTrue(result.any { it.id == "popover-1" }, "Popover surveys should be in matching results")
        assertTrue(result.any { it.id == "widget-1" }, "Widget surveys should be in matching results")
    }
}
