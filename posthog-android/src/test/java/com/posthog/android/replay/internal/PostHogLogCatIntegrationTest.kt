package com.posthog.android.replay.internal

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.PostHogInterface
import com.posthog.android.API_KEY
import com.posthog.android.PostHogAndroidConfig
import com.posthog.internal.PostHogRemoteConfig
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [26])
internal class PostHogLogCatIntegrationTest {
    private val mockPostHog = mock<PostHogInterface>()
    private val mockRemoteConfig = mock<PostHogRemoteConfig>()

    private fun createConfig(captureLogcat: Boolean = true): PostHogAndroidConfig {
        return PostHogAndroidConfig(API_KEY).apply {
            sessionReplayConfig.captureLogcat = captureLogcat
        }
    }

    private fun getSut(config: PostHogAndroidConfig): PostHogLogCatIntegration {
        return PostHogLogCatIntegration(config)
    }

    @BeforeTest
    fun setUp() {
        // Reset static integrationInstalled state
        val config = createConfig()
        val dummy = PostHogLogCatIntegration(config)
        dummy.uninstall()
    }

    @Test
    fun `install sets integrationInstalled to true`() {
        val config = createConfig()
        val sut = getSut(config)

        assertFalse(sut.isInstalled())

        sut.install(mockPostHog)

        assertTrue(sut.isInstalled())
        sut.uninstall()
    }

    @Test
    fun `install does nothing when captureLogcat is disabled`() {
        val config = createConfig(captureLogcat = false)
        val sut = getSut(config)

        sut.install(mockPostHog)

        assertFalse(sut.isInstalled())
    }

    @Test
    fun `uninstall resets integrationInstalled to false`() {
        val config = createConfig()
        val sut = getSut(config)
        sut.install(mockPostHog)
        assertTrue(sut.isInstalled())

        sut.uninstall()

        assertFalse(sut.isInstalled())
    }

    @Test
    fun `onRemoteConfig does nothing when remoteConfigHolder is null`() {
        val config = createConfig()
        val sut = getSut(config)
        sut.install(mockPostHog)
        assertTrue(sut.isInstalled())

        // remoteConfigHolder is null by default
        sut.onRemoteConfig()

        // State unchanged — still installed
        assertTrue(sut.isInstalled())
        sut.uninstall()
    }

    @Test
    fun `onRemoteConfig uninstalls when remote console log recording is disabled`() {
        val config = createConfig()
        config.remoteConfigHolder = mockRemoteConfig
        whenever(mockRemoteConfig.isRemoteConsoleLogRecordingEnabled()).thenReturn(false)

        val sut = getSut(config)
        sut.install(mockPostHog)
        assertTrue(sut.isInstalled())

        sut.onRemoteConfig()

        assertFalse(sut.isInstalled())
    }

    @Test
    fun `onRemoteConfig keeps integration installed when remote console log recording is enabled`() {
        val config = createConfig()
        config.remoteConfigHolder = mockRemoteConfig
        whenever(mockRemoteConfig.isRemoteConsoleLogRecordingEnabled()).thenReturn(true)

        val sut = getSut(config)
        sut.install(mockPostHog)
        assertTrue(sut.isInstalled())

        sut.onRemoteConfig()

        // Still installed — install is a no-op because already installed
        assertTrue(sut.isInstalled())
        sut.uninstall()
    }

    @Test
    fun `onRemoteConfig does not install when postHog is null`() {
        val config = createConfig()
        config.remoteConfigHolder = mockRemoteConfig
        whenever(mockRemoteConfig.isRemoteConsoleLogRecordingEnabled()).thenReturn(true)

        // Don't call install(), so postHog stays null
        val sut = getSut(config)

        sut.onRemoteConfig()

        assertFalse(sut.isInstalled())
    }

    @Test
    fun `onRemoteConfig can re-install after being disabled`() {
        val config = createConfig()
        config.remoteConfigHolder = mockRemoteConfig

        val sut = getSut(config)
        sut.install(mockPostHog)
        assertTrue(sut.isInstalled())

        // Disable remotely
        whenever(mockRemoteConfig.isRemoteConsoleLogRecordingEnabled()).thenReturn(false)
        sut.onRemoteConfig()
        assertFalse(sut.isInstalled())

        // Re-enable remotely — postHog reference is preserved, so install succeeds
        whenever(mockRemoteConfig.isRemoteConsoleLogRecordingEnabled()).thenReturn(true)
        sut.onRemoteConfig()
        assertTrue(sut.isInstalled())
        sut.uninstall()
    }
}
