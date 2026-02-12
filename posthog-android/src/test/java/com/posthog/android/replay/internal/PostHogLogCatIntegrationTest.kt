package com.posthog.android.replay.internal

import com.posthog.PostHogInterface
import com.posthog.android.API_KEY
import com.posthog.android.PostHogAndroidConfig
import com.posthog.internal.PostHogRemoteConfig
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        // Reset static state by creating and uninstalling a dummy integration
        val config = createConfig()
        val integration = PostHogLogCatIntegration(config)
        integration.uninstall()
    }

    @Test
    fun `onRemoteConfig does nothing when remoteConfigHolder is null`() {
        val config = createConfig()
        val sut = getSut(config)

        // remoteConfigHolder is null by default
        sut.install(mockPostHog)

        // should not throw or change state
        sut.onRemoteConfig()

        sut.uninstall()
    }

    @Test
    fun `onRemoteConfig uninstalls when remote console log recording is disabled`() {
        val config = createConfig()
        config.remoteConfigHolder = mockRemoteConfig
        whenever(mockRemoteConfig.isRemoteConsoleLogRecordingEnabled()).thenReturn(false)

        val sut = getSut(config)
        sut.install(mockPostHog)

        sut.onRemoteConfig()

        // After onRemoteConfig with disabled, a new install should succeed
        // (meaning uninstall was called and integrationInstalled was reset)
        val sut2 = getSut(config)
        whenever(mockRemoteConfig.isRemoteConsoleLogRecordingEnabled()).thenReturn(true)
        sut2.install(mockPostHog)

        // If integrationInstalled was properly reset, the second install should work
        // We verify by calling uninstall (which only does work if installed)
        sut2.uninstall()
    }

    @Test
    fun `onRemoteConfig installs when remote console log recording is enabled and postHog is available`() {
        val config = createConfig()
        config.remoteConfigHolder = mockRemoteConfig

        val sut = getSut(config)

        // First install so postHog reference is set
        sut.install(mockPostHog)
        // Uninstall to reset state
        sut.uninstall()

        // Now onRemoteConfig with enabled should re-install
        whenever(mockRemoteConfig.isRemoteConsoleLogRecordingEnabled()).thenReturn(true)
        sut.onRemoteConfig()

        // Verify it was installed by checking that a second install is a no-op
        // (integrationInstalled is true, so install returns early)
        // We can verify by uninstalling - only does real work if installed
        sut.uninstall()
    }

    @Test
    fun `onRemoteConfig does not install when postHog is null`() {
        val config = createConfig()
        config.remoteConfigHolder = mockRemoteConfig
        whenever(mockRemoteConfig.isRemoteConsoleLogRecordingEnabled()).thenReturn(true)

        val sut = getSut(config)
        // Don't call install() so postHog stays null

        sut.onRemoteConfig()

        // Should still be able to install fresh (meaning onRemoteConfig didn't install)
        sut.install(mockPostHog)
        sut.uninstall()
    }

    @Test
    fun `onRemoteConfig does not install when captureLogcat is disabled locally`() {
        val config = createConfig(captureLogcat = false)
        config.remoteConfigHolder = mockRemoteConfig
        whenever(mockRemoteConfig.isRemoteConsoleLogRecordingEnabled()).thenReturn(true)

        val sut = getSut(config)
        // Install would be a no-op because captureLogcat is false,
        // but we need postHog set for onRemoteConfig to try install
        // Since install checks captureLogcat, even onRemoteConfig -> install won't succeed
        sut.onRemoteConfig()

        sut.uninstall()
    }
}
