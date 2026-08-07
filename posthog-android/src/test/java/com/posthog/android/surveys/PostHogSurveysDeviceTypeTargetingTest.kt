package com.posthog.android.surveys

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.PostHogConfig
import com.posthog.PostHogInterface
import com.posthog.surveys.Survey
import com.posthog.surveys.SurveyConditions
import com.posthog.surveys.SurveyMatchType
import com.posthog.surveys.SurveyType
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
internal class PostHogSurveysDeviceTypeTargetingTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun createIntegration(requireDeviceTypeTargeting: Boolean): PostHogSurveysIntegration {
        val config =
            PostHogConfig("test-api-key").apply {
                surveys = true
                surveysConfig.requireDeviceTypeTargeting = requireDeviceTypeTargeting
            }
        val integration = PostHogSurveysIntegration(context, config)
        val fake = mock<PostHogInterface>()
        whenever(fake.isFeatureEnabled(any(), any(), any())).thenReturn(true)
        integration.install(fake)
        return integration
    }

    private fun createSurvey(
        id: String,
        deviceTypes: List<String>?,
        deviceTypesMatchType: SurveyMatchType? = null,
    ): Survey {
        return Survey(
            id = id,
            name = "Test Survey $id",
            type = SurveyType.API,
            questions = emptyList(),
            description = null,
            featureFlagKeys = null,
            linkedFlagKey = null,
            targetingFlagKey = null,
            internalTargetingFlagKey = null,
            conditions =
                SurveyConditions(
                    url = null,
                    urlMatchType = null,
                    selector = null,
                    deviceTypes = deviceTypes,
                    deviceTypesMatchType = deviceTypesMatchType,
                    seenSurveyWaitPeriodInDays = null,
                    events = null,
                ),
            appearance = null,
            currentIteration = null,
            currentIterationStartDate = null,
            startDate = java.util.Date(),
            endDate = null,
            schedule = null,
        )
    }

    private fun isEligible(
        integration: PostHogSurveysIntegration,
        surveyId: String,
    ): Boolean {
        return integration.getActiveMatchingSurveys().any { it.id == surveyId }
    }

    @Test
    fun `default config allows surveys with missing device types`() {
        val integration = createIntegration(requireDeviceTypeTargeting = false)
        val survey = createSurvey("s1", deviceTypes = null)
        integration.onSurveysLoaded(listOf(survey))

        assertTrue(isEligible(integration, "s1"))
    }

    @Test
    fun `default config allows surveys with empty device types`() {
        val integration = createIntegration(requireDeviceTypeTargeting = false)
        val survey = createSurvey("s1", deviceTypes = emptyList())
        integration.onSurveysLoaded(listOf(survey))

        assertTrue(isEligible(integration, "s1"))
    }

    @Test
    fun `requireDeviceTypeTargeting excludes surveys with missing device types`() {
        val integration = createIntegration(requireDeviceTypeTargeting = true)
        val survey = createSurvey("s1", deviceTypes = null)
        integration.onSurveysLoaded(listOf(survey))

        assertFalse(isEligible(integration, "s1"))
    }

    @Test
    fun `requireDeviceTypeTargeting excludes surveys with empty device types`() {
        val integration = createIntegration(requireDeviceTypeTargeting = true)
        val survey = createSurvey("s1", deviceTypes = emptyList())
        integration.onSurveysLoaded(listOf(survey))

        assertFalse(isEligible(integration, "s1"))
    }

    @Test
    fun `requireDeviceTypeTargeting includes surveys with matching device type`() {
        val integration = createIntegration(requireDeviceTypeTargeting = true)
        val survey =
            createSurvey(
                id = "s1",
                deviceTypes = listOf("Mobile"),
                deviceTypesMatchType = SurveyMatchType.EXACT,
            )
        integration.onSurveysLoaded(listOf(survey))

        assertTrue(isEligible(integration, "s1"))
    }

    @Test
    fun `requireDeviceTypeTargeting excludes surveys with non-matching device type`() {
        val integration = createIntegration(requireDeviceTypeTargeting = true)
        val survey =
            createSurvey(
                id = "s1",
                deviceTypes = listOf("Desktop"),
                deviceTypesMatchType = SurveyMatchType.EXACT,
            )
        integration.onSurveysLoaded(listOf(survey))

        assertFalse(isEligible(integration, "s1"))
    }

    @Test
    fun `requireDeviceTypeTargeting supports IS_NOT match operator`() {
        val integration = createIntegration(requireDeviceTypeTargeting = true)
        val survey =
            createSurvey(
                id = "s1",
                deviceTypes = listOf("Desktop"),
                deviceTypesMatchType = SurveyMatchType.IS_NOT,
            )
        integration.onSurveysLoaded(listOf(survey))

        assertTrue(isEligible(integration, "s1"))
    }

    @Test
    fun `requireDeviceTypeTargeting supports I_CONTAINS match operator`() {
        val integration = createIntegration(requireDeviceTypeTargeting = true)
        val survey =
            createSurvey(
                id = "s1",
                deviceTypes = listOf("mob"),
                deviceTypesMatchType = SurveyMatchType.I_CONTAINS,
            )
        integration.onSurveysLoaded(listOf(survey))

        assertTrue(isEligible(integration, "s1"))
    }
}
