package com.posthog.surveys

import org.junit.Test
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class SurveyBinaryCompatibilityTest {
    private val legacyParameterTypes =
        arrayOf(
            String::class.java, String::class.java, SurveyType::class.java, List::class.java,
            String::class.java, List::class.java, String::class.java, String::class.java,
            String::class.java, SurveyConditions::class.java, SurveyAppearance::class.java,
            Integer::class.java, Date::class.java, Date::class.java, Date::class.java,
            SurveySchedule::class.java, Map::class.java,
        )

    private fun legacyArguments(): Array<Any?> =
        arrayOf(
            "survey", "Survey", SurveyType.POPOVER, emptyList<SurveyQuestion>(),
            null, null, null, null, null, null, null, null, null, null, null, null, null,
        )

    @Test
    fun `legacy constructor and its Kotlin defaults remain callable`() {
        val constructor = Survey::class.java.getDeclaredConstructor(*legacyParameterTypes)
        val survey = constructor.newInstance(*legacyArguments())
        assertEquals("survey", survey.id)
        assertEquals(null, survey.enablePartialResponses)

        val defaultConstructor =
            Survey::class.java.getDeclaredConstructor(
                *legacyParameterTypes,
                Int::class.javaPrimitiveType,
                Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"),
            )
        val withDefaults = defaultConstructor.newInstance(*legacyArguments(), 1 shl 16, null)
        assertEquals(survey, withDefaults)
    }

    @Test
    fun `legacy copy and Kotlin default copy preserve partial responses`() {
        val survey =
            Survey(
                "survey", "Survey", SurveyType.POPOVER, emptyList(),
                null, null, null, null, null, null, null, null, null, null, null, null,
                enablePartialResponses = true,
            )
        assertEquals(true, survey.copy(name = "Renamed").enablePartialResponses)
        assertEquals(false, survey.copy(enablePartialResponses = false).enablePartialResponses)
        val copy = Survey::class.java.getDeclaredMethod("copy", *legacyParameterTypes)
        val copied = copy.invoke(survey, *legacyArguments()) as Survey
        assertEquals(survey, copied)
        assertTrue(copied.enablePartialResponses == true)

        val defaultCopy =
            Survey::class.java.getDeclaredMethod(
                "copy\$default",
                Survey::class.java,
                *legacyParameterTypes,
                Int::class.javaPrimitiveType,
                Any::class.java,
            )
        val copiedWithDefaults =
            defaultCopy.invoke(null, survey, *arrayOfNulls<Any>(17), (1 shl 17) - 1, null) as Survey
        assertEquals(survey, copiedWithDefaults)
    }
}
