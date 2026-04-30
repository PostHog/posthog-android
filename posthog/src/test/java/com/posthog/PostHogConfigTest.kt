package com.posthog

import com.posthog.internal.PostHogNoOpLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogConfigTest {
    private val config = PostHogConfig(API_KEY)

    @Test
    fun `host is set app posthog com by default`() {
        assertEquals("https://us.i.posthog.com", config.host)
    }

    @Test
    fun `trims whitespace-sensitive config values`() {
        val config = PostHogConfig(" \n$API_KEY\t ", " \nhttps://eu.i.posthog.com/\t ")

        assertEquals(API_KEY, config.apiKey)
        assertEquals("https://eu.i.posthog.com/", config.host)
    }

    @Test
    fun `defaults null api key to empty string`() {
        val config = PostHogConfig(null, "https://api.posthog.com")

        assertEquals("", config.apiKey)
    }

    @Test
    fun `defaults a blank host after trimming whitespace`() {
        val config = PostHogConfig(API_KEY, " \n\t ")

        assertEquals(PostHogConfig.DEFAULT_HOST, config.host)
    }

    @Test
    fun `debug is disabled by default`() {
        assertFalse(config.debug)
    }

    @Test
    fun `optOut is disabled by default`() {
        assertFalse(config.optOut)
    }

    @Test
    fun `beforeSendList is empty by default`() {
        assertTrue(config.beforeSendList.isEmpty())
    }

    @Test
    fun `sendFeatureFlagEvent is enabled by default`() {
        assertTrue(config.sendFeatureFlagEvent)
    }

    @Test
    fun `preloadFeatureFlags is enabled by default`() {
        assertTrue(config.preloadFeatureFlags)
    }

    @Test
    fun `flushAt is 20 by default`() {
        assertEquals(20, config.flushAt)
    }

    @Test
    fun `maxQueueSize is 1000 by default`() {
        assertEquals(1000, config.maxQueueSize)
    }

    @Test
    fun `maxBatchSize is 50 by default`() {
        assertEquals(50, config.maxBatchSize)
    }

    @Test
    fun `flushIntervalSeconds is 30s by default`() {
        assertEquals(30, config.flushIntervalSeconds)
    }

    @Test
    fun `encryption is not set by default`() {
        assertNull(config.encryption)
    }

    @Test
    fun `tracingHeaders is not set by default`() {
        assertNull(config.tracingHeaders)
    }

    @Test
    fun `tracingHeaders copies assigned lists`() {
        val tracingHeaders = mutableListOf("api.example.com")

        config.tracingHeaders = tracingHeaders
        tracingHeaders.add("other.example.com")

        assertEquals(listOf("api.example.com"), config.tracingHeaders)
    }

    @Test
    fun `tracingHeaders returns a snapshot`() {
        config.tracingHeaders = listOf("api.example.com")

        val tracingHeaders = config.tracingHeaders as MutableList<String>
        tracingHeaders.add("other.example.com")

        assertEquals(listOf("api.example.com"), config.tracingHeaders)
    }

    @Test
    fun `onFeatureFlags is not set by default`() {
        assertNull(config.onFeatureFlags)
    }

    @Test
    fun `logger uses NoOp by default`() {
        assertTrue(config.logger is PostHogNoOpLogger)
    }

    @Test
    fun `sdk name is set to java by default`() {
        assertEquals("posthog-java", config.sdkName)
    }

    @Test
    fun `sdk version is set the java sdk by default`() {
        assertEquals(BuildConfig.VERSION_NAME, config.sdkVersion)
    }

    @Test
    fun `user agent is returned correctly if changed`() {
        config.sdkName = "posthog-android"
        assertEquals("posthog-android/${BuildConfig.VERSION_NAME}", config.userAgent)
    }

    @Test
    fun `user agent is set the java sdk by default`() {
        assertEquals("posthog-java/${BuildConfig.VERSION_NAME}", config.userAgent)
    }

    @Test
    fun `adds integration`() {
        val integration = FakePostHogIntegration()
        config.addIntegration(integration)

        assertEquals(1, config.integrations.size)
    }

    @Test
    fun `removes integration`() {
        val integration = FakePostHogIntegration()

        config.addIntegration(integration)
        config.removeIntegration(integration)

        assertEquals(0, config.integrations.size)
    }
}
