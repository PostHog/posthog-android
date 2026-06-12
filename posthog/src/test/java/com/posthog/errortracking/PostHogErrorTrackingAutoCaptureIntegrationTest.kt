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

    private fun getSut(
        autoCapture: Boolean = true,
        ignoredExceptionTypes: List<String> = emptyList(),
    ): PostHogErrorTrackingAutoCaptureIntegration {
        whenever(mockConfig.errorTrackingConfig).thenReturn(
            PostHogErrorTrackingConfig().apply {
                this.autoCapture = autoCapture
                this.ignoredExceptionTypes.addAll(ignoredExceptionTypes)
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
    fun `uncaughtException skips capture when throwable class is in ignoredExceptionTypes`() {
        whenever(mockConfig.remoteConfigHolder).thenReturn(mockRemoteConfig)
        whenever(mockRemoteConfig.isAutocaptureExceptionsEnabled()).thenReturn(true)
        whenever(mockAdapter.getDefaultUncaughtExceptionHandler()).thenReturn(mockExceptionHandler)

        val thread = Thread.currentThread()
        // Simulates the React Native scenario: posthog-js has already captured the
        // fatal JS error; React Native rethrows it natively as JavascriptException,
        // and the SDK should not emit a duplicate $exception event.
        val throwable = ReactNativeJavascriptExceptionStub("Unhandled JS Exception: ReferenceError")

        val integration =
            getSut(ignoredExceptionTypes = listOf(ReactNativeJavascriptExceptionStub::class.java.name))
        integration.install(mockPostHog)

        integration.uncaughtException(thread, throwable)

        verify(mockPostHog, never()).captureException(any<PostHogThrowable>(), anyOrNull())
        // The downstream handler still runs so the process terminates / RN's red-box
        // appears as it would without PostHog installed.
        verify(mockExceptionHandler).uncaughtException(thread, throwable)

        integration.uninstall()
    }

    @Test
    fun `uncaughtException skips capture when ignored class is anywhere in the cause chain`() {
        whenever(mockConfig.remoteConfigHolder).thenReturn(mockRemoteConfig)
        whenever(mockRemoteConfig.isAutocaptureExceptionsEnabled()).thenReturn(true)
        whenever(mockAdapter.getDefaultUncaughtExceptionHandler()).thenReturn(mockExceptionHandler)

        val thread = Thread.currentThread()
        // The outermost type is RuntimeException, not the ignored type, but the
        // cause chain contains the ignored type. Real RN apps wrap the JS exception
        // inside platform-level wrappers, so walking the chain matters.
        val inner = ReactNativeJavascriptExceptionStub("inner")
        val outer = RuntimeException("outer", inner)

        val integration =
            getSut(ignoredExceptionTypes = listOf(ReactNativeJavascriptExceptionStub::class.java.name))
        integration.install(mockPostHog)

        integration.uncaughtException(thread, outer)

        verify(mockPostHog, never()).captureException(any<PostHogThrowable>(), anyOrNull())
        verify(mockExceptionHandler).uncaughtException(thread, outer)

        integration.uninstall()
    }

    @Test
    fun `uncaughtException still captures when throwable class is not in ignoredExceptionTypes`() {
        whenever(mockConfig.remoteConfigHolder).thenReturn(mockRemoteConfig)
        whenever(mockRemoteConfig.isAutocaptureExceptionsEnabled()).thenReturn(true)
        whenever(mockAdapter.getDefaultUncaughtExceptionHandler()).thenReturn(mockExceptionHandler)

        val thread = Thread.currentThread()
        val throwable = RuntimeException("Genuine native crash")

        val integration =
            getSut(ignoredExceptionTypes = listOf("com.facebook.react.common.JavascriptException"))
        integration.install(mockPostHog)

        integration.uncaughtException(thread, throwable)

        verify(mockPostHog).captureException(any<PostHogThrowable>(), anyOrNull())
        verify(mockExceptionHandler).uncaughtException(thread, throwable)

        integration.uninstall()
    }

    /**
     * Local stand-in for `com.facebook.react.common.JavascriptException`. The real type
     * lives in React Native, which isn't (and shouldn't be) a test dependency of the
     * SDK. The ignored-exception filter is purely class-name based: each test that
     * uses this stub registers its own JVM name
     * (`...PostHogErrorTrackingAutoCaptureIntegrationTest$ReactNativeJavascriptExceptionStub`)
     * as the ignored FQCN. That exercises the matching logic without requiring RN on
     * the classpath. The stub name intentionally differs from the real RN class name —
     * the production code path is identical either way.
     */
    private class ReactNativeJavascriptExceptionStub(message: String) : RuntimeException(message)

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
