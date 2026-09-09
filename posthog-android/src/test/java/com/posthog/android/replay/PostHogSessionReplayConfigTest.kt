package com.posthog.android.replay

import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters
import kotlin.test.Test
import kotlin.test.assertEquals

internal class PostHogSessionReplayConfigTest {
    @RunWith(Parameterized::class)
    class ScreenshotScaleTest(private val input: Float, private val expected: Float) {
        companion object {
            @JvmStatic
            @Parameters(name = "scale={0}, effective={1}")
            fun scales(): List<Array<Float>> =
                listOf(
                    arrayOf(-Float.MAX_VALUE, 0.1f),
                    arrayOf(-1f, 0.1f),
                    arrayOf(0f, 0.1f),
                    arrayOf(Float.MIN_VALUE, 0.1f),
                    arrayOf(0.05f, 0.1f),
                    arrayOf(0.1f, 0.1f),
                    arrayOf(0.333f, 0.333f),
                    arrayOf(0.5f, 0.5f),
                    arrayOf(1f, 1f),
                    arrayOf(2f, 1f),
                    arrayOf(Float.MAX_VALUE, 1f),
                    arrayOf(Float.NaN, 1f),
                    arrayOf(Float.NEGATIVE_INFINITY, 1f),
                    arrayOf(Float.POSITIVE_INFINITY, 1f),
                )
        }

        @Test
        fun `scale getter returns the normalized assignment`() {
            val config = PostHogSessionReplayConfig()
            config.screenshotScale = 0.5f
            config.screenshotScale = input

            assertEquals(expected, config.screenshotScale)
            assertEquals(30, config.screenshotCompressionQuality)
            assertEquals(PostHogScreenshotColorMode.ARGB_8888, config.screenshotColorMode)
        }
    }

    @RunWith(Parameterized::class)
    class ScreenshotCompressionQualityTest(private val input: Int, private val expected: Int) {
        companion object {
            @JvmStatic
            @Parameters(name = "compressionQuality={0}, effective={1}")
            fun qualities(): List<Array<Int>> =
                listOf(
                    arrayOf(Int.MIN_VALUE, 0),
                    arrayOf(-1, 0),
                    arrayOf(0, 0),
                    arrayOf(30, 30),
                    arrayOf(100, 100),
                    arrayOf(101, 100),
                    arrayOf(Int.MAX_VALUE, 100),
                )
        }

        @Test
        fun `compression quality getter returns the clamped assignment`() {
            val config = PostHogSessionReplayConfig()
            config.screenshotCompressionQuality = input

            assertEquals(expected, config.screenshotCompressionQuality)
            assertEquals(1f, config.screenshotScale)
            assertEquals(PostHogScreenshotColorMode.ARGB_8888, config.screenshotColorMode)
        }
    }
}
