package com.posthog.surveys

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class SurveyScheduleTest {
    @Test
    fun `survey with schedule always can activate repeatedly`() {
        val survey = createSurveyWithSchedule("always")

        assertTrue(canActivateRepeatedly(survey))
    }

    @Test
    fun `survey with schedule once cannot activate repeatedly by default`() {
        val survey = createSurveyWithSchedule("once")

        assertFalse(canActivateRepeatedly(survey))
    }

    @Test
    fun `survey with schedule recurring cannot activate repeatedly by default`() {
        val survey = createSurveyWithSchedule("recurring")

        assertFalse(canActivateRepeatedly(survey))
    }

    @Test
    fun `survey with null schedule cannot activate repeatedly by default`() {
        val survey = createSurveyWithSchedule(null)

        assertFalse(canActivateRepeatedly(survey))
    }

    @Test
    fun `survey with repeatedActivation events can activate repeatedly`() {
        val eventConditions =
            SurveyEventConditions(
                repeatedActivation = true,
                values =
                    listOf(
                        SurveyEventCondition(name = "app_opened"),
                    ),
            )
        val conditions =
            SurveyConditions(
                url = null,
                urlMatchType = null,
                selector = null,
                deviceTypes = null,
                deviceTypesMatchType = null,
                seenSurveyWaitPeriodInDays = null,
                events = eventConditions,
            )
        val survey = createSurveyWithConditions(conditions, null)

        assertTrue(canActivateRepeatedly(survey))
    }

    @Test
    fun `survey with repeatedActivation events and schedule always can activate repeatedly`() {
        val eventConditions =
            SurveyEventConditions(
                repeatedActivation = true,
                values =
                    listOf(
                        SurveyEventCondition(name = "app_opened"),
                    ),
            )
        val conditions =
            SurveyConditions(
                url = null,
                urlMatchType = null,
                selector = null,
                deviceTypes = null,
                deviceTypesMatchType = null,
                seenSurveyWaitPeriodInDays = null,
                events = eventConditions,
            )
        val survey = createSurveyWithConditions(conditions, "always")

        assertTrue(canActivateRepeatedly(survey))
    }

    @Test
    fun `survey with repeatedActivation false and events cannot activate repeatedly by default`() {
        val eventConditions =
            SurveyEventConditions(
                repeatedActivation = false,
                values =
                    listOf(
                        SurveyEventCondition(name = "app_opened"),
                    ),
            )
        val conditions =
            SurveyConditions(
                url = null,
                urlMatchType = null,
                selector = null,
                deviceTypes = null,
                deviceTypesMatchType = null,
                seenSurveyWaitPeriodInDays = null,
                events = eventConditions,
            )
        val survey = createSurveyWithConditions(conditions, null)

        assertFalse(canActivateRepeatedly(survey))
    }

    @Test
    fun `survey with repeatedActivation false but schedule always can activate repeatedly`() {
        val eventConditions =
            SurveyEventConditions(
                repeatedActivation = false,
                values =
                    listOf(
                        SurveyEventCondition(name = "app_opened"),
                    ),
            )
        val conditions =
            SurveyConditions(
                url = null,
                urlMatchType = null,
                selector = null,
                deviceTypes = null,
                deviceTypesMatchType = null,
                seenSurveyWaitPeriodInDays = null,
                events = eventConditions,
            )
        val survey = createSurveyWithConditions(conditions, "always")

        assertTrue(canActivateRepeatedly(survey))
    }

    private fun createSurveyWithSchedule(schedule: String?): Survey {
        return Survey(
            id = "test-survey",
            name = "Test Survey",
            type = SurveyType.POPOVER,
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
            startDate = null,
            endDate = null,
            schedule = schedule,
        )
    }

    private fun createSurveyWithConditions(
        conditions: SurveyConditions?,
        schedule: String?,
    ): Survey {
        return Survey(
            id = "test-survey",
            name = "Test Survey",
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
            startDate = null,
            endDate = null,
            schedule = schedule,
        )
    }

    private fun hasEvents(survey: Survey): Boolean {
        return survey.conditions?.events?.values?.isNotEmpty() == true
    }

    private fun canActivateRepeatedly(survey: Survey): Boolean {
        return (survey.conditions?.events?.repeatedActivation == true && hasEvents(survey)) ||
            survey.schedule == "always"
    }
}
