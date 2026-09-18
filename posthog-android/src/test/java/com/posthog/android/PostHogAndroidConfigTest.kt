package com.posthog.android

import com.posthog.android.replay.PostHogScreenshotColorMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class PostHogAndroidConfigTest {
    private val config = PostHogAndroidConfig(API_KEY)

    @Test
    fun `captureApplicationLifecycleEvents sets given apiKey`() {
        assertEquals(API_KEY, config.apiKey)
    }

    @Test
    fun `trims whitespace-sensitive config values`() {
        val config = PostHogAndroidConfig(" \n$API_KEY\t ")

        assertEquals(API_KEY, config.apiKey)
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
        assertEquals(1f, config.sessionReplayConfig.screenshotScale)
        assertEquals(PostHogScreenshotColorMode.ARGB_8888, config.sessionReplayConfig.screenshotColorMode)
        assertFalse(config.sessionReplayConfig.screenshot)
    }

    @Test
    fun `screenshot settings are independent of capture enablement`() {
        config.sessionReplayConfig.screenshotScale = 0.5f
        config.sessionReplayConfig.screenshotCompressionQuality = 60
        config.sessionReplayConfig.screenshotColorMode = PostHogScreenshotColorMode.RGB_565

        assertEquals(0.5f, config.sessionReplayConfig.screenshotScale)
        assertEquals(60, config.sessionReplayConfig.screenshotCompressionQuality)
        assertEquals(PostHogScreenshotColorMode.RGB_565, config.sessionReplayConfig.screenshotColorMode)
        assertFalse(config.sessionReplayConfig.screenshot)
    }

    @Test
    fun `screenshot mask alignment verification should be disabled by default`() {
        assertFalse(config.sessionReplayConfig.verifyScreenshotMaskAlignment)
    }
}
