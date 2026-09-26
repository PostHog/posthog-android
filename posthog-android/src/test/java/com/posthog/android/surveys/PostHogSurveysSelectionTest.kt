package com.posthog.android.surveys

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.PostHogConfig
import com.posthog.PostHogInterface
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.surveys.PostHogSurveysDelegate
import com.posthog.surveys.Survey
import com.posthog.surveys.SurveyConditions
import com.posthog.surveys.SurveyEventCondition
import com.posthog.surveys.SurveyEventConditions
import com.posthog.surveys.SurveyFeatureFlagKeyValue
import com.posthog.surveys.SurveyMatchType
import com.posthog.surveys.SurveyType
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
internal class PostHogSurveysSelectionTest {
    private fun survey(conditions: SurveyConditions? = null): Survey =
        Survey(
            id = "eligible", name = "Eligibility", type = SurveyType.POPOVER, questions = emptyList(),
            description = null, featureFlagKeys = null, linkedFlagKey = null, targetingFlagKey = null,
            internalTargetingFlagKey = null, conditions = conditions, appearance = null, currentIteration = null,
            currentIterationStartDate = null, startDate = Date(0), endDate = null, schedule = null,
        )

    private fun conditions(
        devices: List<String>? = null,
        matchType: SurveyMatchType? = null,
        eventNames: List<String>? = null,
        url: String? = null,
        urlMatchType: SurveyMatchType? = null,
    ): SurveyConditions =
        SurveyConditions(
            url,
            urlMatchType,
            null,
            devices,
            matchType,
            null,
            eventNames?.let { SurveyEventConditions(false, it.map { name -> SurveyEventCondition(name) }) },
        )

    private fun assertEligible(
        survey: Survey,
        expected: Boolean,
        device: String = "Mobile",
        event: String? = null,
        flags: Map<String, Any> = emptyMap(),
    ) {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context =
            base.createConfigurationContext(
                Configuration(base.resources.configuration).apply {
                    smallestScreenWidthDp = if (device == "Tablet") 600 else 360
                    uiMode = if (device == "TV") Configuration.UI_MODE_TYPE_TELEVISION else Configuration.UI_MODE_TYPE_NORMAL
                },
            )
        val config =
            PostHogConfig("selection-test").apply {
                cachePreferences = PostHogMemoryPreferences()
                surveys = true
                surveysConfig.surveysDelegate = mock<PostHogSurveysDelegate>()
            }
        val postHog = mock<PostHogInterface>()
        whenever(postHog.isFeatureEnabled(any(), any(), anyOrNull())).thenAnswer {
            val value = flags[it.getArgument<String>(0)]
            value == true || value is String
        }
        whenever(postHog.getFeatureFlag(any(), anyOrNull(), anyOrNull())).thenAnswer { flags[it.getArgument<String>(0)] }
        val integration = PostHogSurveysIntegration(context, config)
        integration.install(postHog)
        try {
            integration.onSurveysLoaded(listOf(survey))
            if (event != null) integration.onEvent(event, null)
            assertEquals(
                if (expected) listOf("eligible") else emptyList(),
                integration.getActiveMatchingSurveys().map { it.id },
                "device=$device, event=$event, conditions=${survey.conditions}, flags=$flags",
            )
        } finally {
            integration.uninstall()
        }
    }

    @Test
    fun `device inclusion and exclusion use the actual Android device type`() {
        for ((device, included) in listOf("Mobile" to true, "Tablet" to true, "TV" to false)) {
            assertEligible(survey(conditions(listOf("Mobile", "Tablet"), SurveyMatchType.EXACT)), included, device)
            assertEligible(survey(conditions(listOf("Mobile", "Tablet"), SurveyMatchType.IS_NOT)), !included, device)
            assertEligible(survey(conditions()), true, device)
            assertEligible(survey(conditions(emptyList())), true, device)
        }
    }

    @Test
    fun `event targeting activates only on one of the configured names`() {
        for (events in listOf(listOf("app_opened"), listOf("app_opened", "feature_used", "settings_viewed"))) {
            val survey = survey(conditions(eventNames = events))
            assertEligible(survey, false)
            assertEligible(survey, false, event = "unrelated")
            events.forEach { assertEligible(survey, true, event = it) }
        }
        assertEligible(survey(conditions()), true)
        assertEligible(survey(conditions()), true, event = "unrelated")
    }

    @Test
    fun `device and event targeting must both match`() {
        val survey = survey(conditions(listOf("Mobile"), SurveyMatchType.EXACT, listOf("app_opened")))
        assertEligible(survey, true, "Mobile", "app_opened")
        assertEligible(survey, false, "Tablet", "app_opened")
        assertEligible(survey, false, "Mobile", "unrelated")
        assertEligible(survey, false, "Tablet", "unrelated")
    }

    @Test
    fun `web URL conditions remain excluded regardless of match operator or native matches`() {
        for (match in listOf(SurveyMatchType.EXACT, SurveyMatchType.I_CONTAINS, SurveyMatchType.NOT_I_CONTAINS, SurveyMatchType.REGEX)) {
            assertEligible(survey(conditions(url = "checkout", urlMatchType = match)), false)
            assertEligible(
                survey(conditions(listOf("Mobile"), SurveyMatchType.EXACT, listOf("checkout_started"), "checkout", match)),
                false,
                event = "checkout_started",
            )
        }
        assertEligible(survey(), true)
    }

    @Test
    fun `linked targeting internal and feature flag references require enabled flags`() {
        val surveys =
            listOf(
                survey().copy(linkedFlagKey = "gate"),
                survey().copy(targetingFlagKey = "gate"),
                survey().copy(internalTargetingFlagKey = "gate"),
                survey().copy(featureFlagKeys = listOf(SurveyFeatureFlagKeyValue("id", "gate"))),
            )
        for (survey in surveys) {
            assertEligible(survey, true, flags = mapOf("gate" to true))
            assertEligible(survey, false, flags = mapOf("gate" to false))
            assertEligible(survey, false, flags = mapOf("unrelated" to true))
        }
    }

    @Test
    fun `all configured feature flag references must match`() {
        val survey =
            survey().copy(
                featureFlagKeys = listOf(SurveyFeatureFlagKeyValue("first", "beta"), SurveyFeatureFlagKeyValue("second", "premium")),
            )
        assertEligible(survey, true, flags = mapOf("beta" to true, "premium" to true))
        assertEligible(survey, false, flags = mapOf("beta" to true, "premium" to false))
        assertEligible(survey, false, flags = mapOf("beta" to false, "premium" to true))
        assertEligible(survey, false, flags = mapOf("beta" to true))
    }
}
