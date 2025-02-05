package com.posthog.android.internal

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.PostHog
import com.posthog.android.API_KEY
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.createPostHogFake
import com.posthog.android.mockPackageInfo
import com.posthog.internal.PostHogMemoryPreferences
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
internal class PostHogAppInstallIntegrationTest {
    private val context = mock<Context>()

    private fun getSut(): PostHogAppInstallIntegration {
        val config =
            PostHogAndroidConfig(API_KEY).apply {
                cachePreferences = PostHogMemoryPreferences()
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
}
