package com.posthog.android

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.PostHogEvent
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogPreferences
import com.posthog.internal.PostHogPreferences.Companion.BUILD
import com.posthog.internal.PostHogPreferences.Companion.VERSION
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
internal class PostHogAndroidAppVersionTest {
    private val context = mock<Context>()
    private val optOutKey = "opt-out"

    @get:Rule
    val tmpDir = TemporaryFolder()

    @Suppress("DEPRECATION")
    private fun launch(
        preferences: PostHogPreferences,
        version: String,
        build: Int,
        captureLifecycle: Boolean,
        configure: PostHogAndroidConfig.() -> Unit = {},
    ): List<PostHogEvent> {
        mockContextAppStart(context, tmpDir)
        context.applicationContext.mockPackageInfo(version, build)
        context.applicationContext.mockDisplayMetrics()
        context.applicationContext.mockAppInfo()
        val events = mutableListOf<PostHogEvent>()
        val config =
            PostHogAndroidConfig(API_KEY).apply {
                cachePreferences = preferences
                captureApplicationLifecycleEvents = captureLifecycle
                remoteConfig = false
                preloadFeatureFlags = false
                configure()
                addBeforeSend {
                    events.add(it)
                    null
                }
            }
        val client = PostHogAndroid.with(context, config)
        try {
            return events.toList()
        } finally {
            client.close()
        }
    }

    @Test
    fun `disabled launch then upgrade with lifecycle enabled captures updated with previous values`() {
        val preferences = PostHogMemoryPreferences()
        assertTrue(launch(preferences, "1.0.0", 1, captureLifecycle = false).isEmpty())

        val event = launch(preferences, "2.0.0", 2, captureLifecycle = true).single()

        assertEquals("Application Updated", event.event)
        assertEquals("1.0.0", event.properties?.get("previous_version"))
        assertEquals(1L, event.properties?.get("previous_build"))
        assertEquals("2.0.0", event.properties?.get("version"))
        assertEquals(2L, event.properties?.get("build"))
    }

    @Test
    fun `enabled first launch captures installed and next upgrade captures updated`() {
        val preferences = PostHogMemoryPreferences()
        assertEquals("Application Installed", launch(preferences, "1.0.0", 1, captureLifecycle = true).single().event)
        assertTrue(launch(preferences, "1.0.0", 1, captureLifecycle = true).isEmpty())
        val event = launch(preferences, "2.0.0", 2, captureLifecycle = true).single()
        assertEquals("Application Updated", event.event)
        assertEquals("1.0.0", event.properties?.get("previous_version"))
        assertEquals(1L, event.properties?.get("previous_build"))
    }

    @Test
    fun `upgrades while disabled record the latest launched version without events`() {
        val preferences = PostHogMemoryPreferences()
        assertTrue(launch(preferences, "1.0.0", 1, captureLifecycle = false).isEmpty())
        assertTrue(launch(preferences, "2.0.0", 2, captureLifecycle = false).isEmpty())
        assertEquals("2.0.0", preferences.getValue(VERSION))
        assertEquals(2L, preferences.getValue(BUILD))

        val event = launch(preferences, "3.0.0", 3, captureLifecycle = true).single()
        assertEquals("Application Updated", event.event)
        assertEquals("2.0.0", event.properties?.get("previous_version"))
        assertEquals(2L, event.properties?.get("previous_build"))
    }

    @Test
    fun `persisted opt out suppresses lifecycle events but retains version accounting`() {
        val preferences = PostHogMemoryPreferences()
        preferences.setValue(optOutKey, true)
        for ((index, captureLifecycle) in listOf(false, true).withIndex()) {
            val build = index + 1
            assertTrue(launch(preferences, "$build.0.0", build, captureLifecycle).isEmpty())
            assertEquals("$build.0.0", preferences.getValue(VERSION))
            assertEquals(build.toLong(), preferences.getValue(BUILD))
        }
        preferences.setValue(optOutKey, false)
        assertTrue(launch(preferences, "2.0.0", 2, captureLifecycle = true).isEmpty())
    }

    @Test
    fun `host owned opt out suppresses lifecycle without persisting consent`() {
        val preferences = PostHogMemoryPreferences()
        preferences.setValue(optOutKey, false)
        assertTrue(
            launch(preferences, "1.0.0", 1, captureLifecycle = true) {
                persistOptOut = false
                optOut = true
            }.isEmpty(),
        )
        assertEquals("1.0.0", preferences.getValue(VERSION))
        assertEquals(1L, preferences.getValue(BUILD))
        assertEquals(false, preferences.getValue(optOutKey))
        assertTrue(
            launch(preferences, "1.0.0", 1, captureLifecycle = true) {
                persistOptOut = false
            }.isEmpty(),
        )
    }

    @Test
    fun `reenabling lifecycle at the same version does not fabricate installed`() {
        val preferences = PostHogMemoryPreferences()
        assertTrue(launch(preferences, "1.0.0", 1, captureLifecycle = false).isEmpty())

        assertTrue(launch(preferences, "1.0.0", 1, captureLifecycle = true).isEmpty())
    }
}
