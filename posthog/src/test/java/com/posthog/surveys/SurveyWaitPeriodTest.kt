package com.posthog.surveys

import com.posthog.internal.surveys.hasWaitPeriodPassed
import java.util.Calendar
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class SurveyWaitPeriodTest {
    @Test
    fun `survey without wait period is not filtered`() {
        val survey = createSurveyWithWaitPeriod(null)
        val now = Date()

        assertTrue(hasWaitPeriodPassed(survey, lastSeenSurveyDate = now, now = now))
    }

    @Test
    fun `survey with wait period passes when no survey was previously seen`() {
        val survey = createSurveyWithWaitPeriod(7)
        val now = Date()

        assertTrue(hasWaitPeriodPassed(survey, lastSeenSurveyDate = null, now = now))
    }

    @Test
    fun `survey with wait period is filtered when period has not elapsed`() {
        val survey = createSurveyWithWaitPeriod(7)
        val now = Date()
        val oneDayAgo = daysAgo(1, now)

        assertFalse(hasWaitPeriodPassed(survey, lastSeenSurveyDate = oneDayAgo, now = now))
    }

    @Test
    fun `survey with wait period is filtered when exactly at period boundary`() {
        val survey = createSurveyWithWaitPeriod(7)
        val now = Date()
        val sevenDaysAgo = daysAgo(7, now)

        assertFalse(hasWaitPeriodPassed(survey, lastSeenSurveyDate = sevenDaysAgo, now = now))
    }

    @Test
    fun `survey with wait period passes when period has elapsed`() {
        val survey = createSurveyWithWaitPeriod(7)
        val now = Date()
        val tenDaysAgo = daysAgo(10, now)

        assertTrue(hasWaitPeriodPassed(survey, lastSeenSurveyDate = tenDaysAgo, now = now))
    }

    @Test
    fun `survey with zero wait period passes when last seen today`() {
        val survey = createSurveyWithWaitPeriod(0)
        val now = Date()

        // ceil of any positive fraction of a day > 0
        assertTrue(hasWaitPeriodPassed(survey, lastSeenSurveyDate = daysAgo(1, now), now = now))
    }

    @Test
    fun `survey without conditions is not filtered`() {
        val survey =
            Survey(
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
                schedule = null,
            )
        val now = Date()

        assertTrue(hasWaitPeriodPassed(survey, lastSeenSurveyDate = now, now = now))
    }

    private fun daysAgo(
        days: Int,
        from: Date,
    ): Date {
        val cal = Calendar.getInstance()
        cal.time = from
        cal.add(Calendar.DAY_OF_YEAR, -days)
        return cal.time
    }

    private fun createSurveyWithWaitPeriod(waitPeriodInDays: Int?): Survey {
        val conditions =
            SurveyConditions(
                url = null,
                urlMatchType = null,
                selector = null,
                deviceTypes = null,
                deviceTypesMatchType = null,
                seenSurveyWaitPeriodInDays = waitPeriodInDays,
                events = null,
            )
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
            schedule = null,
        )
    }
}
