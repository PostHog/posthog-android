package com.posthog.android.surveys

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.posthog.PostHogConfig
import com.posthog.PostHogInterface
import com.posthog.surveys.PostHogSurveysDelegate
import com.posthog.surveys.Survey
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(ParameterizedRobolectricTestRunner::class)
internal class PostHogSurveysFlagEventTest(
    private val prefix: String,
    private val sendFeatureFlagEvent: Boolean?,
) {
    companion object {
        @JvmStatic
        @Parameters(name = "prefix={0}, sendFeatureFlagEvent={1}")
        fun cases(): List<Array<Any?>> =
            listOf(
                arrayOf("survey-targeting-", false),
                arrayOf("", null),
                arrayOf("custom-survey-targeting-", null),
            )
    }

    @Test
    fun `only survey targeting prefixed flags suppress tracking and variant reads stay silent`() {
        val config =
            PostHogConfig("test-api-key").apply {
                surveys = true
                surveysConfig.surveysDelegate = mock<PostHogSurveysDelegate>()
            }
        val postHog = mock<PostHogInterface>()
        whenever(postHog.isFeatureEnabled(any(), any(), anyOrNull())).thenReturn(true)
        whenever(postHog.getFeatureFlag(any(), anyOrNull(), anyOrNull())).thenReturn("blue")
        val survey =
            config.serializer.deserialize<Survey>(
                StringReader(
                    """
                    {
                        "id": "survey", "name": "Survey", "type": "popover", "questions": [],
                        "start_date": "2024-07-23T09:18:18.376Z",
                        "schedule": "once",
                        "linked_flag_key": "${prefix}linked-flag",
                        "targeting_flag_key": "${prefix}targeting-flag",
                        "internal_targeting_flag_key": "${prefix}internal-flag",
                        "feature_flag_keys": [{"key": "flag1", "value": "${prefix}extra-flag"}],
                        "conditions": { "linkedFlagVariant": "blue" }
                    }
                    """.trimIndent(),
                ),
            )
        val integration = PostHogSurveysIntegration(ApplicationProvider.getApplicationContext<Context>(), config)
        integration.install(postHog)
        try {
            integration.onSurveysLoaded(listOf(survey))
            assertEquals(listOf("survey"), integration.getActiveMatchingSurveys().map { it.id })
        } finally {
            integration.uninstall()
        }

        for (key in listOf("linked-flag", "targeting-flag", "internal-flag", "extra-flag")) {
            verify(postHog, atLeastOnce()).isFeatureEnabled(eq("$prefix$key"), any(), eq(sendFeatureFlagEvent))
        }
        verify(postHog, atLeastOnce()).getFeatureFlag(eq("${prefix}linked-flag"), anyOrNull(), eq(false))
    }
}
