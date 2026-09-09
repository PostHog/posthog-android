package com.posthog.android.surveys.compose.internal.ui

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@RunWith(Parameterized::class)
internal class SurveyChoiceOrderTest(private val hasOpenChoice: Boolean) {
    @Test
    fun `disabled shuffle preserves configured order`() {
        assertEquals(listOf(0, 1, 2), surveyChoiceOrder(listOf("A", "B", "Other"), hasOpenChoice, false))
    }

    @Test
    fun `shuffle preserves every choice identity and pins Other`() {
        val choices = listOf("A", "B", "C", "Other")
        val order = surveyChoiceOrder(choices, hasOpenChoice, true)
        assertEquals(choices.indices.toList(), order.sorted())
        assertNotEquals(choices.indices.toList(), order)
        if (hasOpenChoice) assertEquals(choices.lastIndex, order.last())
    }

    @Test
    fun `two regular choices always swap matching web fallback`() {
        val choices = if (hasOpenChoice) listOf("A", "B", "Other") else listOf("A", "B")
        assertEquals(if (hasOpenChoice) listOf(1, 0, 2) else listOf(1, 0), surveyChoiceOrder(choices, hasOpenChoice, true))
    }

    @Test
    fun `small and duplicate lists retain every original index`() {
        for (choices in listOf(emptyList(), listOf("Other"), listOf("A", "Other"), listOf("A", "A", "Other"))) {
            val order = surveyChoiceOrder(choices, hasOpenChoice, true)
            assertEquals(choices.indices.toList(), order.sorted())
            if (hasOpenChoice && choices.isNotEmpty()) assertEquals(choices.lastIndex, order.last())
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "open={0}")
        fun cases(): List<Array<Boolean>> = listOf(arrayOf(false), arrayOf(true))
    }
}
