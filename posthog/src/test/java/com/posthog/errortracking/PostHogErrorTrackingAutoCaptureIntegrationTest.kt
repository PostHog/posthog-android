package com.posthog.errortracking

import com.posthog.PostHogConfig
import com.posthog.PostHogInterface
import com.posthog.internal.PostHogPrintLogger
import com.posthog.internal.PostHogRemoteConfig
import com.posthog.internal.errortracking.PostHogThrowable
import com.posthog.internal.errortracking.UncaughtExceptionHandlerAdapter
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.BeforeTest
import kotlin.test.Test

internal class PostHogErrorTrackingAutoCaptureIntegrationTest {
    private val mockConfig = mock<PostHogConfig>()
    private val mockPostHog = mock<PostHogInterface>()
    private val mockAdapter = mock<UncaughtExceptionHandlerAdapter>()
    private val mockLogger = mock<PostHogPrintLogger>()
    private val mockExceptionHandler = mock<Thread.UncaughtExceptionHandler>()
    private val mockRemoteConfig = mock<PostHogRemoteConfig>()

    @BeforeTest
    fun setUp() {
        whenever(mockConfig.logger).thenReturn(mockLogger)
    }

    private fun getSut(autoCapture: Boolean = true): PostHogErrorTrackingAutoCaptureIntegration {
        whenever(mockConfig.errorTrackingConfig).thenReturn(
            PostHogErrorTrackingConfig().apply {
                this.autoCapture = autoCapture
            },
        )
        return PostHogErrorTrackingAutoCaptureIntegration(mockConfig, mockAdapter)
    }

    @Test
    fun `install does nothing when already installed`() {
        whenever(mockConfig.remoteConfigHolder).thenReturn(mockRemoteConfig)
        whenever(mockRemoteConfig.isAutocaptureExceptionsEnabled()).thenReturn(true)

        val integration = getSut()

        // First install
        integration.install(mockPostHog)

        // Second install should do nothing
        integration.install(mockPostHog)

        verify(mockAdapter, times(1)).setDefaultUncaughtExceptionHandler(integration)

        integration.uninstall()
    }

    @Test
    fun `install does nothing when autoCapture is disabled`() {
        val integration = getSut(false)

        integration.install(mockPostHog)

        verify(mockAdapter, never()).setDefaultUncaughtExceptionHandler(any())

        integration.uninstall()
    }

    @Test
    fun `install sets up exception handler when current handler is null`() {
        whenever(mockConfig.remoteConfigHolder).thenReturn(mockRemoteConfig)
        whenever(mockRemoteConfig.isAutocaptureExceptionsEnabled()).thenReturn(true)
        whenever(mockAdapter.getDefaultUncaughtExceptionHandler()).thenReturn(null)

        val integration = getSut()
        integration.install(mockPostHog)

        verify(mockAdapter).setDefaultUncaughtExceptionHandler(integration)

        integration.uninstall()
    }

    @Test
    fun `install sets up exception handler when current handler is different`() {
        whenever(mockConfig.remoteConfigHolder).thenReturn(mockRemoteConfig)
        whenever(mockRemoteConfig.isAutocaptureExceptionsEnabled()).thenReturn(true)
        whenever(mockAdapter.getDefaultUncaughtExceptionHandler()).thenReturn(mockExceptionHandler)

        val integration = getSut()
        integration.install(mockPostHog)

        verify(mockAdapter).setDefaultUncaughtExceptionHandler(integration)

        integration.uninstall()
    }

    @Test
    fun `install does not replace current handler when it is already PostHogErrorTrackingAutoCaptureIntegration`() {
        val existingIntegration = getSut()
        whenever(mockAdapter.getDefaultUncaughtExceptionHandler()).thenReturn(existingIntegration)

        val integration = getSut()
        integration.install(mockPostHog)

        verify(mockAdapter, never()).setDefaultUncaughtExceptionHandler(any())

        integration.uninstall()
    }

    @Test
    fun `uninstall does nothing when not installed`() {
        val integration = getSut()

        integration.uninstall()

        verify(mockAdapter, never()).setDefaultUncaughtExceptionHandler(any())

        integration.uninstall()
    }

    @Test
    fun `uninstall restores original exception handler and resets state`() {
        whenever(mockConfig.remoteConfigHolder).thenReturn(mockRemoteConfig)
        whenever(mockRemoteConfig.isAutocaptureExceptionsEnabled()).thenReturn(true)
        whenever(mockAdapter.getDefaultUncaughtExceptionHandler()).thenReturn(mockExceptionHandler)

        val integration = getSut()
        integration.install(mockPostHog)
        integration.uninstall()

        verify(mockAdapter).setDefaultUncaughtExceptionHandler(mockExceptionHandler)

        integration.uninstall()
    }

    @Test
    fun `uncaughtException captures exception and flushes when postHog is available`() {
        val thread = Thread.currentThread()
        val throwable = RuntimeException("Test exception")

        val integration = getSut()
        integration.install(mockPostHog)

        integration.uncaughtException(thread, throwable)

        verify(mockPostHog).captureException(any<PostHogThrowable>(), anyOrNull())

        integration.uninstall()
    }

    @Test
    fun `uncaughtException calls default handler after capturing exception`() {
        whenever(mockConfig.remoteConfigHolder).thenReturn(mockRemoteConfig)
        whenever(mockRemoteConfig.isAutocaptureExceptionsEnabled()).thenReturn(true)
        whenever(mockAdapter.getDefaultUncaughtExceptionHandler()).thenReturn(mockExceptionHandler)

        val thread = Thread.currentThread()
        val throwable = RuntimeException("Test exception")

        val integration = getSut()
        integration.install(mockPostHog)

        integration.uncaughtException(thread, throwable)

        verify(mockExceptionHandler).uncaughtException(thread, throwable)

        integration.uninstall()
    }

    @Test
    fun `onRemoteConfig does nothing when remoteConfigHolder is null`() {
        whenever(mockConfig.remoteConfigHolder).thenReturn(mockRemoteConfig)
        whenever(mockRemoteConfig.isAutocaptureExceptionsEnabled()).thenReturn(true)

        val integration = getSut()
        integration.install(mockPostHog)

        // Verify handler was installed
        verify(mockAdapter).setDefaultUncaughtExceptionHandler(integration)

        // Set remoteConfigHolder to null before onRemoteConfig
        whenever(mockConfig.remoteConfigHolder).thenReturn(null)
        integration.onRemoteConfig()

        // remoteConfigHolder is null → autocaptureExceptionsEnabled defaults to false → uninstall
        // uninstall restores original handler (null) — total 2 calls (install + uninstall)
        verify(mockAdapter, times(2)).setDefaultUncaughtExceptionHandler(anyOrNull())

        integration.uninstall()
    }

    @Test
    fun `onRemoteConfig uninstalls when autocapture exceptions is disabled`() {
        whenever(mockConfig.remoteConfigHolder).thenReturn(mockRemoteConfig)
        whenever(mockRemoteConfig.isAutocaptureExceptionsEnabled()).thenReturn(true)
        whenever(mockAdapter.getDefaultUncaughtExceptionHandler()).thenReturn(mockExceptionHandler)

        val integration = getSut()
        integration.install(mockPostHog)

        // install sets our handler
        verify(mockAdapter).setDefaultUncaughtExceptionHandler(integration)

        // Disable remotely
        whenever(mockRemoteConfig.isAutocaptureExceptionsEnabled()).thenReturn(false)
        integration.onRemoteConfig()

        // uninstall restores the original handler
        verify(mockAdapter).setDefaultUncaughtExceptionHandler(mockExceptionHandler)

        integration.uninstall()
    }

    @Test
    fun `onRemoteConfig keeps handler when autocapture exceptions is enabled`() {
        whenever(mockConfig.remoteConfigHolder).thenReturn(mockRemoteConfig)
        whenever(mockRemoteConfig.isAutocaptureExceptionsEnabled()).thenReturn(true)

        val integration = getSut()
        integration.install(mockPostHog)

        verify(mockAdapter, times(1)).setDefaultUncaughtExceptionHandler(integration)

        integration.onRemoteConfig()

        // install is a no-op since already installed — still only called once
        verify(mockAdapter, times(1)).setDefaultUncaughtExceptionHandler(any())

        integration.uninstall()
    }

    @Test
    fun `onRemoteConfig does not install when postHog is null`() {
        whenever(mockConfig.remoteConfigHolder).thenReturn(mockRemoteConfig)
        whenever(mockRemoteConfig.isAutocaptureExceptionsEnabled()).thenReturn(true)

        // Don't call install(), so postHog stays null
        val integration = getSut()

        integration.onRemoteConfig()

        verify(mockAdapter, never()).setDefaultUncaughtExceptionHandler(any())
    }

    @Test
    fun `onRemoteConfig can re-install after being disabled`() {
        whenever(mockConfig.remoteConfigHolder).thenReturn(mockRemoteConfig)
        whenever(mockRemoteConfig.isAutocaptureExceptionsEnabled()).thenReturn(true)
        whenever(mockAdapter.getDefaultUncaughtExceptionHandler()).thenReturn(mockExceptionHandler)

        val integration = getSut()
        integration.install(mockPostHog)

        // install sets our handler
        verify(mockAdapter).setDefaultUncaughtExceptionHandler(integration)

        // Disable remotely
        whenever(mockRemoteConfig.isAutocaptureExceptionsEnabled()).thenReturn(false)
        integration.onRemoteConfig()

        // uninstall restores original handler
        verify(mockAdapter).setDefaultUncaughtExceptionHandler(mockExceptionHandler)

        // Re-enable remotely — postHog reference is preserved, so install succeeds
        whenever(mockRemoteConfig.isAutocaptureExceptionsEnabled()).thenReturn(true)
        integration.onRemoteConfig()

        // Handler set to our integration again (total 2 times)
        verify(mockAdapter, times(2)).setDefaultUncaughtExceptionHandler(integration)

        integration.uninstall()
    }
}
