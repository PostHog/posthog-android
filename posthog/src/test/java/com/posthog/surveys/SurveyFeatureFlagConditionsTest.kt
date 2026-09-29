package com.posthog.surveys

import kotlin.test.Test
import kotlin.test.assertEquals

internal class SurveyFeatureFlagConditionsTest {
    @Test
    fun `survey with linked flag key should be associated with that flag`() {
        val survey =
            Survey(
                id = "test-survey",
                name = "Test Survey",
                type = SurveyType.POPOVER,
                questions = emptyList(),
                description = null,
                featureFlagKeys = null,
                linkedFlagKey = "linked-flag",
                targetingFlagKey = null,
                internalTargetingFlagKey = null,
                conditions = null,
                appearance = null,
                currentIteration = null,
                currentIterationStartDate = null,
                startDate = null,
                endDate = null,
                schedule = null,
            )

        assertEquals("linked-flag", survey.linkedFlagKey)
    }
}
