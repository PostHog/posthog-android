package com.posthog.errortracking

import com.posthog.PostHogConfig
import com.posthog.PostHogInterface
import com.posthog.internal.PostHogPrintLogger
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
        whenever(mockAdapter.getDefaultUncaughtExceptionHandler()).thenReturn(null)

        val integration = getSut()
        integration.install(mockPostHog)

        verify(mockAdapter).setDefaultUncaughtExceptionHandler(integration)

        integration.uninstall()
    }

    @Test
    fun `install sets up exception handler when current handler is different`() {
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
        whenever(mockAdapter.getDefaultUncaughtExceptionHandler()).thenReturn(mockExceptionHandler)

        val thread = Thread.currentThread()
        val throwable = RuntimeException("Test exception")

        val integration = getSut()
        integration.install(mockPostHog)

        integration.uncaughtException(thread, throwable)

        verify(mockExceptionHandler).uncaughtException(thread, throwable)

        integration.uninstall()
    }
}
