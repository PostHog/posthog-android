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
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(ParameterizedRobolectricTestRunner::class)
internal class PostHogSurveysLinkedFlagVariantTest(
    private val linkedKey: String?,
    private val variant: String?,
    private val matches: Boolean,
) {
    companion object {
        @JvmStatic
        @Parameters(name = "key={0}, variant={1}, matches={2}")
        fun cases(): List<Array<Any?>> =
            listOf(
                arrayOf("linked-blue", "blue", true),
                arrayOf("linked-blue", "red", false),
                arrayOf("linked-blue", "Blue", false),
                arrayOf("linked-enabled", "blue", false),
                arrayOf("linked-disabled", "blue", false),
                arrayOf("missing-flag", "blue", false),
                arrayOf("linked-blue", "any", true),
                arrayOf("linked-enabled", "any", true),
                arrayOf("linked-disabled", "any", false),
                arrayOf("missing-flag", "any", false),
                arrayOf("linked-blue", null, true),
                arrayOf("linked-blue", "", true),
                arrayOf(null, "blue", true),
                arrayOf("", "blue", true),
            )
    }

    @Test
    fun `matches decoded variant without bypassing other targeting`() {
        val config =
            PostHogConfig("test-api-key").apply {
                surveys = true
                surveysConfig.surveysDelegate = mock<PostHogSurveysDelegate>()
            }
        val flagValues = mapOf("linked-blue" to "blue", "linked-enabled" to true, "linked-disabled" to false)
        val postHog = mock<PostHogInterface>()
        whenever(postHog.isFeatureEnabled(any(), any(), anyOrNull())).thenAnswer {
            val value = flagValues[it.getArgument<String>(0)]
            value == true || value is String
        }
        whenever(postHog.getFeatureFlag(any(), anyOrNull(), anyOrNull())).thenAnswer {
            flagValues[it.getArgument<String>(0)]
        }
        val survey =
            config.serializer.deserialize<Survey>(
                StringReader(
                    """
                    {
                        "id": "survey", "name": "Survey", "type": "popover", "questions": [],
                        "start_date": "2024-07-23T09:18:18.376Z",
                        "linked_flag_key": ${linkedKey?.let { "\"$it\"" } ?: "null"},
                        "conditions": { "linkedFlagVariant": ${variant?.let { "\"$it\"" } ?: "null"} }
                    }
                    """.trimIndent(),
                ),
            )
        assertEquals(variant, survey.conditions?.copy()?.linkedFlagVariant)
        val integration = PostHogSurveysIntegration(ApplicationProvider.getApplicationContext<Context>(), config)
        integration.install(postHog)
        try {
            integration.onSurveysLoaded(listOf(survey, survey.copy(id = "blocked", targetingFlagKey = "disabled-targeting")))
            assertEquals(if (matches) listOf("survey") else emptyList(), integration.getActiveMatchingSurveys().map { it.id })
        } finally {
            integration.uninstall()
        }
    }
}
