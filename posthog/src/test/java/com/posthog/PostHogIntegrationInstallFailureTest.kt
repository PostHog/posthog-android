package com.posthog

import com.posthog.internal.PostHogMemoryPreferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class PostHogIntegrationInstallFailureTest {
    private val testLogger = TestLogger()
    private var client: PostHogInterface? = null

    @AfterTest
    fun cleanup() {
        client?.close()
    }

    private fun getSut(failure: Throwable): FakePostHogIntegration {
        val healthy = FakePostHogIntegration()
        val broken =
            object : PostHogIntegration {
                override fun install(postHog: PostHogInterface) {
                    throw failure
                }
            }

        @Suppress("DEPRECATION")
        client =
            PostHog.with(
                PostHogConfig(API_KEY).apply {
                    logger = testLogger
                    cachePreferences = PostHogMemoryPreferences()
                    remoteConfig = false
                    preloadFeatureFlags = false
                    addBeforeSend { null }
                    addIntegration(broken)
                    addIntegration(healthy)
                },
            )
        return healthy
    }

    @Test
    fun `a missing artifact is reported as a warning that names the artifact`() {
        val healthy = getSut(NoClassDefFoundError("Failed resolution of: Lcurtains/Curtains;"))

        val warning = testLogger.warnings.single()
        assertTrue(warning.contains("com.squareup.curtains:curtains"))
        assertTrue(warning.contains("stay off for the whole process"))
        // one broken integration must not stop the others
        assertTrue(healthy.installed)
    }

    @Test
    fun `a missing class of an unknown artifact is still reported as a warning`() {
        getSut(ClassNotFoundException("com.example.Missing"))

        assertTrue(testLogger.warnings.single().contains("probably excludes an artifact"))
    }

    @Test
    fun `any other install failure stays a debug message`() {
        getSut(RuntimeException("boom"))

        assertTrue(testLogger.warnings.isEmpty())
        assertTrue(testLogger.messages.any { it.contains("failed to install") })
        assertFalse(testLogger.messages.any { it.contains("artifact") })
    }
}
