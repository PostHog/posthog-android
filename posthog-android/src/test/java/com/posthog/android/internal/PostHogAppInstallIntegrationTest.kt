package com.posthog.android.internal

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.PostHog
import com.posthog.android.API_KEY
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.createPostHogFake
import com.posthog.android.mockPackageInfo
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogPreferences
import com.posthog.internal.PostHogPreferences.Companion.BUILD
import com.posthog.internal.PostHogPreferences.Companion.VERSION
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
internal class PostHogAppInstallIntegrationTest {
    private val context = mock<Context>()

    private fun getSut(preferences: PostHogPreferences = PostHogMemoryPreferences()): PostHogAppInstallIntegration {
        val config =
            PostHogAndroidConfig(API_KEY).apply {
                cachePreferences = preferences
            }
        return PostHogAppInstallIntegration(context, config)
    }

    @BeforeTest
    fun `set up`() {
        PostHog.resetSharedInstance()
    }

    @Test
    fun `install captures app installed`() {
        val sut = getSut()

        context.mockPackageInfo("1.0.0", 1)

        val fake = createPostHogFake()

        sut.install(fake)

        assertEquals("Application Installed", fake.event)
        assertEquals("1.0.0", fake.properties?.get("version"))
        assertEquals(1L, fake.properties?.get("build"))

        sut.uninstall()
    }

    @Test
    fun `install captures app updated`() {
        val sut = getSut()

        context.mockPackageInfo("1.0.0", 1)

        val fake = createPostHogFake()

        sut.install(fake)

        context.mockPackageInfo("2.0.0", 2)

        sut.uninstall()
        sut.install(fake)

        assertEquals("Application Updated", fake.event)
        assertEquals("1.0.0", fake.properties?.get("previous_version"))
        assertEquals(1L, fake.properties?.get("previous_build"))
        assertEquals("2.0.0", fake.properties?.get("version"))
        assertEquals(2L, fake.properties?.get("build"))

        sut.uninstall()
    }

    @Test
    fun `install does not capture if not installed or updated`() {
        val sut = getSut()

        context.mockPackageInfo("1.0.0", 1)

        val fake = createPostHogFake()

        sut.install(fake)

        assertEquals(1, fake.captures)

        sut.uninstall()
        sut.install(fake)

        // sanity check
        assertEquals(1, fake.captures)

        sut.uninstall()
    }

    @Test
    fun `install defers until preferences are readable instead of firing a spurious install event`() {
        val delegate = PostHogMemoryPreferences()
        var available = false
        val preferences =
            object : PostHogPreferences by delegate {
                override fun isAvailable(): Boolean = available
            }
        // an existing install whose stored version is unreadable while locked
        delegate.setValue(VERSION, "1.0.0")
        delegate.setValue(BUILD, 1L)
        val sut = getSut(preferences)

        context.mockPackageInfo("2.0.0", 2)

        val fake = createPostHogFake()

        sut.install(fake)

        assertEquals(0, fake.captures)

        available = true
        sut.install(fake)

        assertEquals("Application Updated", fake.event)
        assertEquals("1.0.0", fake.properties?.get("previous_version"))
        assertEquals(1L, fake.properties?.get("previous_build"))

        sut.uninstall()
    }
}
