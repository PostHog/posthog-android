package com.posthog.android.surveys

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.PostHogConfig
import com.posthog.PostHogInterface
import com.posthog.surveys.Survey
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import java.io.StringReader
import kotlin.test.Test

@RunWith(AndroidJUnit4::class)
internal class PostHogSurveysNoRendererTest {
    @Test
    fun `default delegate does not evaluate survey flags on load or event`() {
        val config = PostHogConfig("test-api-key")
        val postHog = mock<PostHogInterface>()
        val survey =
            config.serializer.deserialize<Survey>(
                StringReader(
                    """
                    {
                        "id": "survey", "name": "Survey", "type": "popover", "questions": [],
                        "start_date": "2024-07-23T09:18:18.376Z",
                        "linked_flag_key": "linked-flag",
                        "conditions": {
                            "events": { "values": [{ "name": "purchase" }] }
                        }
                    }
                    """.trimIndent(),
                ),
            )
        val integration = PostHogSurveysIntegration(ApplicationProvider.getApplicationContext<Context>(), config)
        integration.install(postHog)
        try {
            integration.onSurveysLoaded(listOf(survey))
            integration.onEvent("purchase", emptyMap())
        } finally {
            integration.uninstall()
        }

        verify(postHog, never()).isFeatureEnabled(any(), any(), anyOrNull())
        verify(postHog, never()).getFeatureFlag(any(), anyOrNull())
    }
}
