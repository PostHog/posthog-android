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
import com.posthog.surveys.SurveyMatchType
import com.posthog.surveys.SurveyType
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests that surveys with web-only display-targeting conditions (a CSS
 * `selector` and/or `url` match) are excluded from active matching surveys on
 * Android, since those conditions cannot be evaluated outside a browser DOM.
 *
 * This mirrors the exclusion already shipped in posthog-ios and
 * posthog-react-native, and matches the canonical `surveys` contract in
 * PostHog/sdk-specs.
 */
@RunWith(AndroidJUnit4::class)
internal class PostHogSurveysWebConditionsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

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
        conditions: SurveyConditions?,
    ): Survey {
        return Survey(
            id = id,
            name = "Test Survey $id",
            type = SurveyType.POPOVER,
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

    private fun conditions(
        url: String? = null,
        urlMatchType: SurveyMatchType? = null,
        selector: String? = null,
        deviceTypes: List<String>? = null,
        events: SurveyEventConditions? = null,
    ): SurveyConditions {
        return SurveyConditions(
            url = url,
            urlMatchType = urlMatchType,
            selector = selector,
            deviceTypes = deviceTypes,
            deviceTypesMatchType = null,
            seenSurveyWaitPeriodInDays = null,
            events = events,
        )
    }

    @Test
    fun `getActiveMatchingSurveys excludes surveys whose only condition is a url`() {
        val delegate = RecordingDelegate()
        val integration = createIntegration(delegate)
        val webSurvey =
            createSurvey(
                "url-only",
                conditions(url = "https://example.com/pricing", urlMatchType = SurveyMatchType.I_CONTAINS),
            )
        integration.onSurveysLoaded(listOf(webSurvey))

        val result = integration.getActiveMatchingSurveys()

        assertFalse(
            result.any { it.id == "url-only" },
            "A survey whose only condition is a url should be excluded on Android",
        )
    }

    @Test
    fun `getActiveMatchingSurveys excludes surveys whose only condition is a selector`() {
        val delegate = RecordingDelegate()
        val integration = createIntegration(delegate)
        val webSurvey = createSurvey("selector-only", conditions(selector = "#my-button"))
        integration.onSurveysLoaded(listOf(webSurvey))

        val result = integration.getActiveMatchingSurveys()

        assertFalse(
            result.any { it.id == "selector-only" },
            "A survey whose only condition is a CSS selector should be excluded on Android",
        )
    }

    @Test
    fun `showNextSurvey does not render web-only surveys`() {
        val delegate = RecordingDelegate()
        val integration = createIntegration(delegate)
        val webSurvey = createSurvey("web-only", conditions(url = "https://example.com", selector = "#btn"))
        integration.onSurveysLoaded(listOf(webSurvey))

        assertFalse(
            delegate.renderedSurveyIds.contains("web-only"),
            "Web-only surveys should not be auto-displayed",
        )
    }

    @Test
    fun `getActiveMatchingSurveys excludes surveys with url and native device type targeting`() {
        val delegate = RecordingDelegate()
        val integration = createIntegration(delegate)
        val survey =
            createSurvey(
                "url-plus-device",
                conditions(url = "https://example.com", deviceTypes = listOf("Mobile")),
            )
        integration.onSurveysLoaded(listOf(survey))

        val result = integration.getActiveMatchingSurveys()

        assertFalse(
            result.any { it.id == "url-plus-device" },
            "A survey with unevaluable URL targeting should be excluded even when its device type matches",
        )
    }

    @Test
    fun `getActiveMatchingSurveys excludes surveys with selector and native event targeting`() {
        val delegate = RecordingDelegate()
        val integration = createIntegration(delegate)
        val survey =
            createSurvey(
                "selector-plus-event",
                conditions(
                    selector = "#my-button",
                    events = SurveyEventConditions(repeatedActivation = null, values = listOf(SurveyEventCondition("my_event"))),
                ),
            )
        integration.onSurveysLoaded(listOf(survey))

        // Activate the survey's event so it passes the event-activation filter and
        // isolates the assertion to the unevaluable web condition.
        integration.onEvent("my_event", null)

        val result = integration.getActiveMatchingSurveys()

        assertFalse(
            result.any { it.id == "selector-plus-event" },
            "A survey with unevaluable selector targeting should be excluded even after its event activates",
        )
    }

    @Test
    fun `getActiveMatchingSurveys keeps surveys with no conditions`() {
        val delegate = RecordingDelegate()
        val integration = createIntegration(delegate)
        val survey = createSurvey("no-conditions", conditions = null)
        integration.onSurveysLoaded(listOf(survey))

        val result = integration.getActiveMatchingSurveys()

        assertTrue(
            result.any { it.id == "no-conditions" },
            "A survey with no display conditions should remain eligible",
        )
    }
}
