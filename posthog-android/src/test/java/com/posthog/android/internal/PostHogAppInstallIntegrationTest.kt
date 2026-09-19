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

    private fun getSut(
        preferences: PostHogPreferences = PostHogMemoryPreferences(),
        captureLifecycle: Boolean = true,
    ): PostHogAppInstallIntegration {
        val config =
            PostHogAndroidConfig(API_KEY).apply {
                cachePreferences = preferences
                captureApplicationLifecycleEvents = captureLifecycle
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
    fun `disabled capture does not read or buffer version writes while storage is unavailable`() {
        val delegate = PostHogMemoryPreferences()
        delegate.setValue(VERSION, "1.0.0")
        delegate.setValue(BUILD, 1L)
        var available = false
        val preferences =
            object : PostHogPreferences by delegate {
                override fun isAvailable(): Boolean = available

                override fun getValue(
                    key: String,
                    defaultValue: Any?,
                ): Any? {
                    check(available)
                    return delegate.getValue(key, defaultValue)
                }

                override fun setValue(
                    key: String,
                    value: Any,
                ) {
                    check(available)
                    delegate.setValue(key, value)
                }
            }
        val sut = getSut(preferences, captureLifecycle = false)
        val fake = createPostHogFake()
        context.mockPackageInfo("2.0.0", 2)
        try {
            sut.install(fake)
            assertEquals("1.0.0", delegate.getValue(VERSION))
            assertEquals(1L, delegate.getValue(BUILD))

            available = true
            sut.install(fake)
            assertEquals("2.0.0", delegate.getValue(VERSION))
            assertEquals(2L, delegate.getValue(BUILD))
            assertEquals(0, fake.captures)
        } finally {
            sut.uninstall()
        }
    }

    @Test
    fun `enabled owner suppresses secondary events but not bookkeeping and disabled uninstall cannot release it`() {
        val ownerPreferences = PostHogMemoryPreferences()
        val secondaryPreferences = PostHogMemoryPreferences()
        val owner = getSut(ownerPreferences)
        val disabled = getSut(PostHogMemoryPreferences(), captureLifecycle = false)
        val secondary = getSut(secondaryPreferences)
        val fake = createPostHogFake()
        context.mockPackageInfo("1.0.0", 1)
        try {
            owner.install(fake)
            disabled.install(fake)
            disabled.uninstall()
            secondary.install(fake)
            secondary.uninstall()
            secondary.install(fake)
            assertEquals(1L, ownerPreferences.getValue(BUILD))
            assertEquals(1L, secondaryPreferences.getValue(BUILD))
            assertEquals(1, fake.captures)

            owner.uninstall()
            context.mockPackageInfo("2.0.0", 2)
            secondary.install(fake)
            assertEquals("Application Updated", fake.event)
            assertEquals(2, fake.captures)
            assertEquals(2L, secondaryPreferences.getValue(BUILD))
        } finally {
            owner.uninstall()
            disabled.uninstall()
            secondary.uninstall()
        }
    }

    @Test
    fun `same build records current version name without emitting an update`() {
        val preferences = PostHogMemoryPreferences()
        preferences.setValue(VERSION, "1.0.0")
        // Legacy integer builds still compare equal to the current long build.
        preferences.setValue(BUILD, 1)
        val sut = getSut(preferences)
        val fake = createPostHogFake()
        context.mockPackageInfo("1.0.1", 1)
        try {
            sut.install(fake)
            assertEquals("1.0.1", preferences.getValue(VERSION))
            assertEquals(1L, preferences.getValue(BUILD))
            assertEquals(0, fake.captures)
        } finally {
            sut.uninstall()
        }
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
