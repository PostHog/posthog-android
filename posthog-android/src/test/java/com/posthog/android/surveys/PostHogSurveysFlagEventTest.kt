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
import org.robolectric.RobolectricTestRunner
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
internal class PostHogSurveysFlagEventTest {
    @Test
    fun `survey targeting flags are read without sending the feature flag called event`() {
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
                        "linked_flag_key": "linked-flag",
                        "targeting_flag_key": "targeting-flag",
                        "internal_targeting_flag_key": "internal-flag",
                        "feature_flag_keys": [{"key": "flag1", "value": "extra-flag"}],
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

        // Reading a survey's targeting flags must not report $feature_flag_called: the app never asked for them.
        for (key in listOf("linked-flag", "targeting-flag", "extra-flag")) {
            verify(postHog, atLeastOnce()).isFeatureEnabled(eq(key), any(), eq(false))
        }
    }
}
