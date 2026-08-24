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
    fun `survey adapters return null for malformed objects`() {
        val adapters =
            listOf<Pair<Class<*>, (PostHogConfig) -> Any>>(
                SurveyQuestion::class.java to { GsonSurveyQuestionAdapter(it) },
                SurveyTextContentType::class.java to { GsonSurveyTextContentTypeAdapter(it) },
                SurveyType::class.java to { GsonSurveyTypeAdapter(it) },
            )

        adapters.forEach { (targetType, adapterFactory) ->
            val logger = TestLogger()
            val gson =
                GsonBuilder()
                    .registerTypeAdapter(targetType, adapterFactory(config(logger)))
                    .create()

            val result = gson.fromJson("{}", targetType)

            assertNull(result, "${targetType.simpleName} adapter should return null")
            assertMalformedObjectLogged(logger)
        }
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
