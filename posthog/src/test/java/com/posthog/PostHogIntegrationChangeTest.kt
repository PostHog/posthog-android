package com.posthog

import com.posthog.internal.PostHogMemoryPreferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class PostHogIntegrationChangeTest {
    private var changes = 0
    private var uninstalls = 0
    private var changeCallback: () -> Unit = {}
    private val integration =
        object : PostHogIntegration {
            override fun onChange() {
                changes++
                changeCallback()
            }

            override fun uninstall() {
                uninstalls++
            }
        }

    @Suppress("DEPRECATION")
    private val client =
        PostHog.with(
            PostHogConfig(API_KEY).apply {
                cachePreferences = PostHogMemoryPreferences()
                remoteConfig = false
                preloadFeatureFlags = false
                addBeforeSend { null }
                addIntegration(integration)
            },
        )

    @AfterTest
    fun cleanup() {
        client.close()
    }

    @Test
    fun `identity consent screen and session changes notify integrations`() {
        val actions: List<() -> Unit> =
            listOf(
                { client.identify("user") },
                { client.reset() },
                { client.screen("Next") },
                { client.optOut() },
                { client.endSession() },
                { client.startSession() },
            )
        for (action in actions) {
            val before = changes
            action()
            assertTrue(changes > before)
        }
    }

    @Test
    fun `repeated opt out does not notify without a consent change`() {
        client.optOut()
        val before = changes
        client.optOut()
        assertEquals(before, changes)
        assertTrue(client.isOptOut())
    }

    @Test
    fun `nested callback failures do not prevent consent changes`() {
        val before = changes
        var nested = false
        changeCallback = {
            if (!nested) {
                nested = true
                client.screen("Nested")
            }
            throw IllegalStateException("test")
        }
        client.optOut()
        assertTrue(client.isOptOut())
        assertEquals(before + 2, changes)
        client.optIn()
        assertFalse(client.isOptOut())
    }

    @Test
    fun `close notifies once and independently uninstalls despite callback failures`() {
        val before = changes
        changeCallback = { throw IllegalStateException("test") }
        client.close()
        assertEquals(before + 1, changes)
        assertEquals(1, uninstalls)
    }
}
