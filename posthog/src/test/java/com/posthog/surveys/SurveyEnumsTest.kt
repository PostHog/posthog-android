package com.posthog.surveys

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class SurveyEnumsTest {
    @Test
    fun `fromValue resolves every survey enum value`() {
        SurveyType.values().forEach { assertEquals(it, SurveyType.fromValue(it.value)) }
        SurveyQuestionType.values().forEach { assertEquals(it, SurveyQuestionType.fromValue(it.value)) }
        SurveyTextContentType.values().forEach { assertEquals(it, SurveyTextContentType.fromValue(it.value)) }
        SurveyMatchType.values().forEach { assertEquals(it, SurveyMatchType.fromValue(it.value)) }
        SurveyAppearancePosition.values().forEach { assertEquals(it, SurveyAppearancePosition.fromValue(it.value)) }
        SurveyAppearanceWidgetType.values().forEach { assertEquals(it, SurveyAppearanceWidgetType.fromValue(it.value)) }
        SurveyRatingDisplayType.values().forEach { assertEquals(it, SurveyRatingDisplayType.fromValue(it.value)) }
        SurveyQuestionBranchingType.values().forEach { assertEquals(it, SurveyQuestionBranchingType.fromValue(it.value)) }
        SurveySchedule.values().forEach { assertEquals(it, SurveySchedule.fromValue(it.value)) }
    }

    @Test
    fun `fromValue returns null for unknown survey enum values`() {
        assertNull(SurveyType.fromValue("unknown"))
        assertNull(SurveyQuestionType.fromValue("unknown"))
        assertNull(SurveyTextContentType.fromValue("unknown"))
        assertNull(SurveyMatchType.fromValue("unknown"))
        assertNull(SurveyAppearancePosition.fromValue("unknown"))
        assertNull(SurveyAppearanceWidgetType.fromValue("unknown"))
        assertNull(SurveyRatingDisplayType.fromValue("unknown"))
        assertNull(SurveyQuestionBranchingType.fromValue("unknown"))
        assertNull(SurveySchedule.fromValue("unknown"))
    }
}
