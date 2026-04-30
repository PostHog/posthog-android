package com.posthog.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class PostHogAndroidConfigTest {
    private val config = PostHogAndroidConfig(API_KEY)

    @Test
    fun `captureApplicationLifecycleEvents sets given apiKey`() {
        assertEquals(API_KEY, config.apiKey)
    }

    @Test
    fun `defaults null api key to empty string`() {
        val config = PostHogAndroidConfig(null)

        assertEquals("", config.apiKey)
    }

    @Test
    fun `captureApplicationLifecycleEvents should be enabled by default`() {
        assertTrue(config.captureApplicationLifecycleEvents)
    }

    @Test
    fun `captureDeepLinks should be enabled by default`() {
        assertTrue(config.captureDeepLinks)
    }

    @Test
    fun `captureScreenViews should be enabled by default`() {
        assertTrue(config.captureScreenViews)
    }
}
