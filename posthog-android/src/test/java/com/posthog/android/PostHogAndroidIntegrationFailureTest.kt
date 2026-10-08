package com.posthog.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.PostHogIntegration
import com.posthog.android.PostHogAndroid.Companion.addIntegrationSafely
import com.posthog.internal.PostHogLogger
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
internal class PostHogAndroidIntegrationFailureTest {
    private class RecordingLogger : PostHogLogger {
        val warnings = mutableListOf<String>()

        override fun log(message: String) {}

        override fun logWarning(message: String) {
            warnings.add(message)
        }

        override fun isEnabled(): Boolean = true
    }

    private val logger = RecordingLogger()
    private val config = PostHogAndroidConfig("apiKey").apply { this.logger = this@PostHogAndroidIntegrationFailureTest.logger }

    @Test
    fun `an integration that cannot be built is reported and the others are kept`() {
        val healthy = object : PostHogIntegration {}

        config.addIntegrationSafely("Broken") { throw NoClassDefFoundError("Failed resolution of: Lcurtains/Curtains;") }
        config.addIntegrationSafely("Healthy") { healthy }

        assertEquals(listOf(healthy), config.integrations)
        assertTrue(logger.warnings.single().contains("com.squareup.curtains:curtains"))
    }
}
