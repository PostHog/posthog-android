package com.posthog.android

import kotlin.test.Test
import kotlin.test.assertTrue

internal class PostHogAndroidConfigTest {

    private val config = PostHogAndroidConfig(apiKey)

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
