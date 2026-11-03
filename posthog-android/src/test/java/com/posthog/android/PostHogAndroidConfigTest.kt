package com.posthog.android

import com.posthog.android.replay.PostHogScreenshotColorMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogAndroidConfigTest {
    private val config = PostHogAndroidConfig(PROJECT_TOKEN)

    @Test
    fun `captureApplicationLifecycleEvents sets given projectToken`() {
        assertEquals(PROJECT_TOKEN, config.projectToken)
    }

    @Test
    fun `trims whitespace-sensitive config values`() {
        val config = PostHogAndroidConfig(" \n$PROJECT_TOKEN\t ")

        assertEquals(PROJECT_TOKEN, config.projectToken)
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

    @Test
    fun `screenshot settings preserve the default capture fidelity`() {
        assertEquals(30, config.sessionReplayConfig.screenshotCompressionQuality)
        assertNull(config.sessionReplayConfig.screenshotScale)
        assertEquals(PostHogScreenshotColorMode.ARGB_8888, config.sessionReplayConfig.screenshotColorMode)
    }

    @Test
    fun `screenshot mask alignment verification should be disabled by default`() {
        assertFalse(config.sessionReplayConfig.verifyScreenshotMaskAlignment)
    }
}
