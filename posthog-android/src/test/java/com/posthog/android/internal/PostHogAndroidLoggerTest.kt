package com.posthog.android.internal

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.android.PostHogAndroidConfig
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLog
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
internal class PostHogAndroidLoggerTest {
    private val config = PostHogAndroidConfig("apiKey").apply { debug = false }
    private val sut = PostHogAndroidLogger(config)

    @BeforeTest
    fun setup() {
        ShadowLog.clear()
    }

    @Test
    fun `a warning reaches Logcat even when debug is off`() {
        sut.logWarning("a dependency is missing")

        assertTrue(ShadowLog.getLogs().any { it.msg == "a dependency is missing" })
    }

    @Test
    fun `a debug message stays out of Logcat when debug is off`() {
        sut.log("some detail")

        assertTrue(ShadowLog.getLogs().none { it.msg == "some detail" })
    }
}
