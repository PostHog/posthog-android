package com.posthog.internal.surveys

import com.google.gson.GsonBuilder
import com.posthog.API_KEY
import com.posthog.PostHogConfig
import com.posthog.TestLogger
import com.posthog.surveys.SurveyQuestion
import com.posthog.surveys.SurveyTextContentType
import com.posthog.surveys.SurveyType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class GsonSurveyAdaptersTest {
    @Test
    fun `question adapter returns null for malformed object`() {
        val logger = TestLogger()
        val gson =
            GsonBuilder()
                .registerTypeAdapter(SurveyQuestion::class.java, GsonSurveyQuestionAdapter(config(logger)))
                .create()

        val result = gson.fromJson("{}", SurveyQuestion::class.java)

        assertNull(result)
        assertMalformedObjectLogged(logger)
    }

    @Test
    fun `text content adapter returns null for malformed object`() {
        val logger = TestLogger()
        val gson =
            GsonBuilder()
                .registerTypeAdapter(
                    SurveyTextContentType::class.java,
                    GsonSurveyTextContentTypeAdapter(config(logger)),
                ).create()

        val result = gson.fromJson("{}", SurveyTextContentType::class.java)

        assertNull(result)
        assertMalformedObjectLogged(logger)
    }

    @Test
    fun `enum adapter returns null for malformed object`() {
        val logger = TestLogger()
        val gson =
            GsonBuilder()
                .registerTypeAdapter(SurveyType::class.java, GsonSurveyTypeAdapter(config(logger)))
                .create()

        val result = gson.fromJson("{}", SurveyType::class.java)

        assertNull(result)
        assertMalformedObjectLogged(logger)
    }

    private fun config(logger: TestLogger): PostHogConfig =
        PostHogConfig(API_KEY).apply {
            this.logger = logger
        }

    private fun assertMalformedObjectLogged(logger: TestLogger) {
        assertEquals(1, logger.messages.size)
        assertTrue(logger.messages.single().startsWith("{} isn't a known type:"))
    }
}
