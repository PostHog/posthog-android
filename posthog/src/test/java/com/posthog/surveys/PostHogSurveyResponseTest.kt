package com.posthog.surveys

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class PostHogSurveyResponseTest {
    @Test
    fun `Text response toResponseValue returns text`() {
        val response = PostHogSurveyResponse.Text("Hello world")
        assertEquals("Hello world", response.toResponseValue())
    }

    @Test
    fun `Text response with empty text returns empty string`() {
        val response = PostHogSurveyResponse.Text("")
        assertEquals("", response.toResponseValue())
    }

    @Test
    fun `SingleChoice response toResponseValue returns selected choice`() {
        val response = PostHogSurveyResponse.SingleChoice("Option A")
        assertEquals("Option A", response.toResponseValue())
    }

    @Test
    fun `MultipleChoice response toResponseValue returns selected choices list`() {
        val choices = listOf("Option A", "Option C")
        val response = PostHogSurveyResponse.MultipleChoice(choices)
        assertEquals(choices, response.toResponseValue())
    }

    @Test
    fun `Rating response toResponseValue returns rating as string`() {
        val response = PostHogSurveyResponse.Rating(8)
        assertEquals("8", response.toResponseValue())
    }

    @Test
    fun `Rating response with negative rating returns negative string`() {
        val response = PostHogSurveyResponse.Rating(-1)
        assertEquals("-1", response.toResponseValue())
    }

    @Test
    fun `Link response with clicked true returns link clicked`() {
        val response = PostHogSurveyResponse.Link(true)
        assertEquals("link clicked", response.toResponseValue())
    }

    @Test
    fun `Link response with clicked false returns null`() {
        val response = PostHogSurveyResponse.Link(false)
        assertNull(response.toResponseValue())
    }
}
