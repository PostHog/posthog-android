package com.posthog.surveys

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class SurveyFeatureFlagConditionsTest {
    @Test
    fun `survey with feature flag keys should match when flag is enabled`() {
        val survey =
            createSurveyWithFeatureFlags(
                listOf(
                    SurveyFeatureFlagKeyValue("beta-feature", "true"),
                ),
            )

        val enabledFlags =
            mapOf(
                "beta-feature" to true,
            )

        assertTrue(shouldShowSurvey(survey, enabledFlags))
    }

    @Test
    fun `survey with feature flag keys should not match when flag is disabled`() {
        val survey =
            createSurveyWithFeatureFlags(
                listOf(
                    SurveyFeatureFlagKeyValue("beta-feature", "true"),
                ),
            )

        val enabledFlags =
            mapOf(
                "beta-feature" to false,
            )

        assertFalse(shouldShowSurvey(survey, enabledFlags))
    }

    @Test
    fun `survey with feature flag keys should not match when flag is missing`() {
        val survey =
            createSurveyWithFeatureFlags(
                listOf(
                    SurveyFeatureFlagKeyValue("beta-feature", "true"),
                ),
            )

        val enabledFlags =
            mapOf(
                "other-feature" to true,
            )

        assertFalse(shouldShowSurvey(survey, enabledFlags))
    }

    @Test
    fun `survey with multiple feature flag keys should match when all flags match`() {
        val survey =
            createSurveyWithFeatureFlags(
                listOf(
                    SurveyFeatureFlagKeyValue("beta-feature", "true"),
                    SurveyFeatureFlagKeyValue("premium-user", "true"),
                ),
            )

        val enabledFlags =
            mapOf(
                "beta-feature" to true,
                "premium-user" to true,
            )

        assertTrue(shouldShowSurvey(survey, enabledFlags))
    }

    @Test
    fun `survey with multiple feature flag keys should not match when any flag doesn't match`() {
        val survey =
            createSurveyWithFeatureFlags(
                listOf(
                    SurveyFeatureFlagKeyValue("beta-feature", "true"),
                    SurveyFeatureFlagKeyValue("premium-user", "true"),
                ),
            )

        val enabledFlags =
            mapOf(
                "beta-feature" to true,
                "premium-user" to false,
            )

        assertFalse(shouldShowSurvey(survey, enabledFlags))
    }

    @Test
    fun `survey with string feature flag values should match when value matches`() {
        val survey =
            createSurveyWithFeatureFlags(
                listOf(
                    SurveyFeatureFlagKeyValue("user-tier", "premium"),
                ),
            )

        val flagValues =
            mapOf(
                "user-tier" to "premium",
            )

        assertTrue(shouldShowSurvey(survey, flagValues))
    }

    @Test
    fun `survey with string feature flag values should not match when value doesn't match`() {
        val survey =
            createSurveyWithFeatureFlags(
                listOf(
                    SurveyFeatureFlagKeyValue("user-tier", "premium"),
                ),
            )

        val flagValues =
            mapOf(
                "user-tier" to "basic",
            )

        assertFalse(shouldShowSurvey(survey, flagValues))
    }

    @Test
    fun `survey with targeting flag key should match when flag is enabled`() {
        val survey = createSurveyWithTargetingFlag("targeting-flag")

        val enabledFlags =
            mapOf(
                "targeting-flag" to true,
            )

        assertTrue(shouldShowSurveyWithTargetingFlag(survey, enabledFlags))
    }

    @Test
    fun `survey with targeting flag key should not match when flag is disabled`() {
        val survey = createSurveyWithTargetingFlag("targeting-flag")

        val enabledFlags =
            mapOf(
                "targeting-flag" to false,
            )

        assertFalse(shouldShowSurveyWithTargetingFlag(survey, enabledFlags))
    }

    @Test
    fun `survey with internal targeting flag key should match when flag is enabled`() {
        val survey = createSurveyWithInternalTargetingFlag("internal-flag")

        val enabledFlags =
            mapOf(
                "internal-flag" to true,
            )

        assertTrue(shouldShowSurveyWithInternalTargetingFlag(survey, enabledFlags))
    }

    @Test
    fun `survey with linked flag key should be associated with that flag`() {
        val survey = createSurveyWithLinkedFlag("linked-flag")

        assertEquals("linked-flag", survey.linkedFlagKey)
    }

    // Helper methods to create test surveys and simulate flag matching logic
    private fun createSurveyWithFeatureFlags(featureFlagKeys: List<SurveyFeatureFlagKeyValue>): Survey {
        return Survey(
            id = "test-survey",
            name = "Test Survey",
            type = SurveyType.POPOVER,
            questions = emptyList(),
            description = null,
            featureFlagKeys = featureFlagKeys,
            linkedFlagKey = null,
            targetingFlagKey = null,
            internalTargetingFlagKey = null,
            conditions = null,
            appearance = null,
            currentIteration = null,
            currentIterationStartDate = null,
            startDate = null,
            endDate = null,
        )
    }

    private fun createSurveyWithTargetingFlag(flagKey: String): Survey {
        return Survey(
            id = "test-survey",
            name = "Test Survey",
            type = SurveyType.POPOVER,
            questions = emptyList(),
            description = null,
            featureFlagKeys = null,
            linkedFlagKey = null,
            targetingFlagKey = flagKey,
            internalTargetingFlagKey = null,
            conditions = null,
            appearance = null,
            currentIteration = null,
            currentIterationStartDate = null,
            startDate = null,
            endDate = null,
        )
    }

    private fun createSurveyWithInternalTargetingFlag(flagKey: String): Survey {
        return Survey(
            id = "test-survey",
            name = "Test Survey",
            type = SurveyType.POPOVER,
            questions = emptyList(),
            description = null,
            featureFlagKeys = null,
            linkedFlagKey = null,
            targetingFlagKey = null,
            internalTargetingFlagKey = flagKey,
            conditions = null,
            appearance = null,
            currentIteration = null,
            currentIterationStartDate = null,
            startDate = null,
            endDate = null,
        )
    }

    private fun createSurveyWithLinkedFlag(flagKey: String): Survey {
        return Survey(
            id = "test-survey",
            name = "Test Survey",
            type = SurveyType.POPOVER,
            questions = emptyList(),
            description = null,
            featureFlagKeys = null,
            linkedFlagKey = flagKey,
            targetingFlagKey = null,
            internalTargetingFlagKey = null,
            conditions = null,
            appearance = null,
            currentIteration = null,
            currentIterationStartDate = null,
            startDate = null,
            endDate = null,
        )
    }

    private fun shouldShowSurvey(
        survey: Survey,
        flagValues: Map<String, Any>,
    ): Boolean {
        val featureFlagKeys = survey.featureFlagKeys ?: return true

        return featureFlagKeys.all { flagKeyValue ->
            val flagValue = flagValues[flagKeyValue.key]
            when (flagValue) {
                is Boolean -> flagValue.toString() == flagKeyValue.value
                is String -> flagValue == flagKeyValue.value
                else -> false
            }
        }
    }

    private fun shouldShowSurveyWithTargetingFlag(
        survey: Survey,
        flagValues: Map<String, Any>,
    ): Boolean {
        val targetingFlagKey = survey.targetingFlagKey ?: return true

        val flagValue = flagValues[targetingFlagKey]
        return flagValue == true
    }

    private fun shouldShowSurveyWithInternalTargetingFlag(
        survey: Survey,
        flagValues: Map<String, Any>,
    ): Boolean {
        val internalTargetingFlagKey = survey.internalTargetingFlagKey ?: return true

        val flagValue = flagValues[internalTargetingFlagKey]
        return flagValue == true
    }
}
