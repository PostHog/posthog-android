package com.posthog.surveys

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class SurveyBranchingLogicTest {
    @Test
    fun `Next branching returns next question index`() {
        val branching = SurveyQuestionBranching.Next
        val currentIndex = 2

        val result = getNextQuestionIndex(branching, currentIndex, "any_response")

        assertEquals(3, result)
        assertFalse(isSurveyCompleted(branching, "any_response"))
    }

    @Test
    fun `End branching completes the survey`() {
        val branching = SurveyQuestionBranching.End
        val currentIndex = 2

        val result = getNextQuestionIndex(branching, currentIndex, "any_response")

        assertEquals(currentIndex, result)
        assertTrue(isSurveyCompleted(branching, "any_response"))
    }

    @Test
    fun `SpecificQuestion branching returns specified index`() {
        val targetIndex = 5
        val branching = SurveyQuestionBranching.SpecificQuestion(targetIndex)
        val currentIndex = 2

        val result = getNextQuestionIndex(branching, currentIndex, "any_response")

        assertEquals(targetIndex, result)
        assertFalse(isSurveyCompleted(branching, "any_response"))
    }

    @Test
    fun `ResponseBased branching with matching string response returns correct index`() {
        val responseValues =
            mapOf(
                "yes" to 3,
                "no" to "end",
                "maybe" to 1,
            )
        val branching = SurveyQuestionBranching.ResponseBased(responseValues)
        val currentIndex = 0

        val result = getNextQuestionIndex(branching, currentIndex, "yes")

        assertEquals(3, result)
        assertFalse(isSurveyCompleted(branching, "yes"))
    }

    @Test
    fun `ResponseBased branching with end string value completes the survey`() {
        val responseValues =
            mapOf(
                "yes" to 3,
                "no" to "end",
                "maybe" to 1,
            )
        val branching = SurveyQuestionBranching.ResponseBased(responseValues)
        val currentIndex = 0

        val result = getNextQuestionIndex(branching, currentIndex, "no")

        assertEquals(currentIndex, result)
        assertTrue(isSurveyCompleted(branching, "no"))
    }

    @Test
    fun `ResponseBased branching with non-matching response defaults to next question`() {
        val responseValues =
            mapOf(
                "yes" to 3,
                "no" to "end",
                "maybe" to 1,
            )
        val branching = SurveyQuestionBranching.ResponseBased(responseValues)
        val currentIndex = 0

        val result = getNextQuestionIndex(branching, currentIndex, "unknown")

        assertEquals(1, result)
        assertFalse(isSurveyCompleted(branching, "unknown"))
    }

    @Test
    fun `ResponseBased branching with numeric response values`() {
        val responseValues =
            mapOf(
                "1" to 1,
                "3" to 2,
                "5" to "end",
            )
        val branching = SurveyQuestionBranching.ResponseBased(responseValues)
        val currentIndex = 0

        assertEquals(1, getNextQuestionIndex(branching, currentIndex, "1"))
        assertEquals(2, getNextQuestionIndex(branching, currentIndex, "3"))
        assertEquals(0, getNextQuestionIndex(branching, currentIndex, "5"))
        assertTrue(isSurveyCompleted(branching, "5"))
    }

    // Helper methods to simulate the branching logic
    private fun getNextQuestionIndex(
        branching: SurveyQuestionBranching,
        currentIndex: Int,
        response: String,
    ): Int {
        return when (branching) {
            is SurveyQuestionBranching.Next -> currentIndex + 1
            is SurveyQuestionBranching.End -> currentIndex
            is SurveyQuestionBranching.SpecificQuestion -> branching.index
            is SurveyQuestionBranching.ResponseBased -> {
                val nextValue = branching.responseValues[response]
                when {
                    nextValue == null -> currentIndex + 1
                    nextValue is Int -> nextValue
                    nextValue == "end" -> currentIndex
                    else -> currentIndex + 1
                }
            }
        }
    }

    private fun isSurveyCompleted(
        branching: SurveyQuestionBranching,
        response: String,
    ): Boolean {
        return when (branching) {
            is SurveyQuestionBranching.End -> true
            is SurveyQuestionBranching.ResponseBased -> {
                val nextValue = branching.responseValues[response]
                nextValue == "end"
            }
            else -> false
        }
    }
}
