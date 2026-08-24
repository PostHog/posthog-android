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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

internal class PostHogErrorTrackingAutoCaptureIntegrationTest {
    private val mockConfig = mock<PostHogConfig>()
    private val mockPostHog = mock<PostHogInterface>()
    private val mockAdapter = mock<UncaughtExceptionHandlerAdapter>()
    private val mockLogger = mock<PostHogPrintLogger>()
    private val mockExceptionHandler = mock<Thread.UncaughtExceptionHandler>()
    private val mockRemoteConfig = mock<PostHogRemoteConfig>()

    // Stateful stand-in for the process JVM handler slot so the adapter reflects installs/restores
    // like the real one, letting the ownership guard in uninstall() be exercised for real.
    private var currentHandler: Thread.UncaughtExceptionHandler? = null

    @BeforeTest
    fun setUp() {
        whenever(mockConfig.logger).thenReturn(mockLogger)
        currentHandler = null
        whenever(mockAdapter.getDefaultUncaughtExceptionHandler()).thenAnswer { currentHandler }
        whenever(mockAdapter.setDefaultUncaughtExceptionHandler(anyOrNull())).thenAnswer {
            currentHandler = it.getArgument(0)
            null
        }
    }

    @AfterTest
    fun tearDown() {
        // Force-reset the process-static install flag so a test that throws before its own
        // uninstall() can't leak install state into the next test's compareAndSet.
        PostHogErrorTrackingAutoCaptureIntegration.resetForTests()
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
        currentHandler = null

        val integration = getSut()
        integration.install(mockPostHog)

        verify(mockAdapter).setDefaultUncaughtExceptionHandler(integration)

        integration.uninstall()
    }

    @Test
    fun `install sets up exception handler when current handler is different`() {
        whenever(mockConfig.remoteConfigHolder).thenReturn(mockRemoteConfig)
        whenever(mockRemoteConfig.isAutocaptureExceptionsEnabled()).thenReturn(true)
        currentHandler = mockExceptionHandler

        val integration = getSut()
        integration.install(mockPostHog)

        verify(mockAdapter).setDefaultUncaughtExceptionHandler(integration)

        integration.uninstall()
    }

    @Test
    fun `install does not replace current handler when it is already PostHogErrorTrackingAutoCaptureIntegration`() {
        val existingIntegration = getSut()
        currentHandler = existingIntegration

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
        currentHandler = mockExceptionHandler

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
        currentHandler = mockExceptionHandler

        val thread = Thread.currentThread()
        val throwable = RuntimeException("Test exception")

        val integration = getSut()
        integration.install(mockPostHog)

        integration.uncaughtException(thread, throwable)

        verify(mockExceptionHandler).uncaughtException(thread, throwable)

        integration.uninstall()
    }

    @Test
    fun `uncaughtException still delegates to the previous handler when capture itself throws`() {
        whenever(mockConfig.remoteConfigHolder).thenReturn(mockRemoteConfig)
        whenever(mockRemoteConfig.isAutocaptureExceptionsEnabled()).thenReturn(true)
        currentHandler = mockExceptionHandler
        whenever(mockPostHog.captureException(any(), anyOrNull())).thenThrow(IllegalStateException("telemetry broke"))

        val thread = Thread.currentThread()
        val throwable = RuntimeException("Test exception")

        val integration = getSut()
        integration.install(mockPostHog)

        // Regression: a throwing capture/flush used to escape uncaughtException, so the previous
        // handler (the app's own crash handling) never ran.
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
        currentHandler = mockExceptionHandler

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
    fun `local-only gate installs without any remote config present`() {
        // No remoteConfigHolder and no errorTrackingConfig stubbed: the default gate reads both, a
        // local-only gate bypasses them entirely. This is the server SDK's path.
        currentHandler = null

        val integration = PostHogErrorTrackingAutoCaptureIntegration(mockConfig, mockAdapter) { true }
        integration.install(mockPostHog)

        verify(mockAdapter).setDefaultUncaughtExceptionHandler(integration)

        integration.uninstall()
    }

    @Test
    fun `local-only gate that is false does not install`() {
        val integration = PostHogErrorTrackingAutoCaptureIntegration(mockConfig, mockAdapter) { false }
        integration.install(mockPostHog)

        verify(mockAdapter, never()).setDefaultUncaughtExceptionHandler(any())

        integration.uninstall()
    }

    @Test
    fun `onRemoteConfig can re-install after being disabled`() {
        whenever(mockConfig.remoteConfigHolder).thenReturn(mockRemoteConfig)
        whenever(mockRemoteConfig.isAutocaptureExceptionsEnabled()).thenReturn(true)
        currentHandler = mockExceptionHandler

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

    // First-launch default-on (issue #648)

    @Test
    fun `install installs by default before remote config arrives on first launch`() {
        // First launch: no cached config and /config not yet fetched, so isAutocaptureExceptionsEnabled()
        // is still false. The handler must install by default so a crash in this window is not missed.
        whenever(mockConfig.remoteConfigHolder).thenReturn(mockRemoteConfig)
        whenever(mockRemoteConfig.hasRemoteConfigFetched()).thenReturn(false)
        whenever(mockRemoteConfig.hasCachedErrorTrackingConfig()).thenReturn(false)
        whenever(mockRemoteConfig.isAutocaptureExceptionsEnabled()).thenReturn(false)

        val integration = getSut()
        integration.install(mockPostHog)

        verify(mockAdapter).setDefaultUncaughtExceptionHandler(integration)

        integration.uninstall()
    }

    @Test
    fun `install does not install when local autoCapture is disabled even without remote config`() {
        // Local off is the primary gate: default-on must never override a host that disabled it.
        whenever(mockConfig.remoteConfigHolder).thenReturn(mockRemoteConfig)
        whenever(mockRemoteConfig.hasRemoteConfigFetched()).thenReturn(false)
        whenever(mockRemoteConfig.hasCachedErrorTrackingConfig()).thenReturn(false)

        val integration = getSut(autoCapture = false)
        integration.install(mockPostHog)

        verify(mockAdapter, never()).setDefaultUncaughtExceptionHandler(any())

        integration.uninstall()
    }

    @Test
    fun `install does not install when cached remote config disables autocapture`() {
        // A prior launch cached an error-tracking config that disables autocapture. That known
        // stance gates installation even before this session's /config fetch completes.
        whenever(mockConfig.remoteConfigHolder).thenReturn(mockRemoteConfig)
        whenever(mockRemoteConfig.hasRemoteConfigFetched()).thenReturn(false)
        whenever(mockRemoteConfig.hasCachedErrorTrackingConfig()).thenReturn(true)
        whenever(mockRemoteConfig.isAutocaptureExceptionsEnabled()).thenReturn(false)

        val integration = getSut()
        integration.install(mockPostHog)

        verify(mockAdapter, never()).setDefaultUncaughtExceptionHandler(any())

        integration.uninstall()
    }

    @Test
    fun `install does not install when fetched remote config disables autocapture`() {
        whenever(mockConfig.remoteConfigHolder).thenReturn(mockRemoteConfig)
        whenever(mockRemoteConfig.hasRemoteConfigFetched()).thenReturn(true)
        whenever(mockRemoteConfig.isAutocaptureExceptionsEnabled()).thenReturn(false)

        val integration = getSut()
        integration.install(mockPostHog)

        verify(mockAdapter, never()).setDefaultUncaughtExceptionHandler(any())

        integration.uninstall()
    }

    @Test
    fun `install installs when cached remote config enables autocapture`() {
        whenever(mockConfig.remoteConfigHolder).thenReturn(mockRemoteConfig)
        whenever(mockRemoteConfig.hasCachedErrorTrackingConfig()).thenReturn(true)
        whenever(mockRemoteConfig.isAutocaptureExceptionsEnabled()).thenReturn(true)

        val integration = getSut()
        integration.install(mockPostHog)

        verify(mockAdapter).setDefaultUncaughtExceptionHandler(integration)

        integration.uninstall()
    }

    @Test
    fun `onRemoteConfig uninstalls default install when freshly loaded config disables autocapture`() {
        // Default-on install on first launch (no config yet), then /config lands disabling autocapture.
        whenever(mockConfig.remoteConfigHolder).thenReturn(mockRemoteConfig)
        whenever(mockRemoteConfig.hasRemoteConfigFetched()).thenReturn(false)
        whenever(mockRemoteConfig.hasCachedErrorTrackingConfig()).thenReturn(false)
        whenever(mockRemoteConfig.isAutocaptureExceptionsEnabled()).thenReturn(false)
        currentHandler = mockExceptionHandler

        val integration = getSut()
        integration.install(mockPostHog)

        // Installed by default even though remote config hasn't confirmed autocapture
        verify(mockAdapter).setDefaultUncaughtExceptionHandler(integration)

        // /config arrives with autocapture disabled → uninstall restores the original handler
        integration.onRemoteConfig(loaded = true)

        verify(mockAdapter).setDefaultUncaughtExceptionHandler(mockExceptionHandler)

        integration.uninstall()
    }

    @Test
    fun `onRemoteConfig keeps default install when freshly loaded config enables autocapture`() {
        whenever(mockConfig.remoteConfigHolder).thenReturn(mockRemoteConfig)
        whenever(mockRemoteConfig.hasRemoteConfigFetched()).thenReturn(false)
        whenever(mockRemoteConfig.hasCachedErrorTrackingConfig()).thenReturn(false)
        whenever(mockRemoteConfig.isAutocaptureExceptionsEnabled()).thenReturn(false)

        val integration = getSut()
        integration.install(mockPostHog)

        verify(mockAdapter, times(1)).setDefaultUncaughtExceptionHandler(integration)

        // /config arrives confirming autocapture enabled → already installed, stays installed
        whenever(mockRemoteConfig.isAutocaptureExceptionsEnabled()).thenReturn(true)
        integration.onRemoteConfig(loaded = true)

        verify(mockAdapter, times(1)).setDefaultUncaughtExceptionHandler(any())

        integration.uninstall()
    }

    // Mid-chain disable/re-enable: when a handler installs after us we can't unlink, so a
    // disabled instance must stay dormant, keep delegating, and never re-link.

    // Install with the app's handler present, let a third-party overlay take the top slot, then
    // have remote config disable autocapture — the integration goes mid-chain-dormant.
    private fun installedThenOverlaidAndRemoteDisabled(): PostHogErrorTrackingAutoCaptureIntegration {
        whenever(mockConfig.remoteConfigHolder).thenReturn(mockRemoteConfig)
        whenever(mockRemoteConfig.isAutocaptureExceptionsEnabled()).thenReturn(true)
        currentHandler = mockExceptionHandler

        val integration = getSut()
        integration.install(mockPostHog)

        currentHandler = mock<Thread.UncaughtExceptionHandler>()
        whenever(mockRemoteConfig.hasRemoteConfigFetched()).thenReturn(true)
        whenever(mockRemoteConfig.isAutocaptureExceptionsEnabled()).thenReturn(false)
        integration.onRemoteConfig(loaded = true)
        return integration
    }

    @Test
    fun `uninstall stops capturing but keeps delegating when another handler installed after us`() {
        val integration = installedThenOverlaidAndRemoteDisabled()

        verify(mockAdapter).setDefaultUncaughtExceptionHandler(integration)
        // We did not clobber the overlay by restoring our saved handler.
        verify(mockAdapter, never()).setDefaultUncaughtExceptionHandler(mockExceptionHandler)

        // A crash still routes through us: we must NOT capture (kill-switch), but must delegate down.
        val thread = Thread.currentThread()
        val throwable = RuntimeException("crash while dormant")
        integration.uncaughtException(thread, throwable)

        verify(mockPostHog, never()).captureException(any<PostHogThrowable>(), anyOrNull())
        verify(mockExceptionHandler).uncaughtException(thread, throwable)

        // Clear the process-wide install flag so it doesn't leak into the next test.
        currentHandler = integration
        integration.uninstall()
    }

    @Test
    fun `onRemoteConfig re-enable resumes capturing without re-linking when mid-chain`() {
        val integration = installedThenOverlaidAndRemoteDisabled()

        // Remote re-enables. We must resume capturing WITHOUT re-linking — re-linking here would
        // point defaultExceptionHandler at the overlay (which delegates to us) and loop to a
        // StackOverflow on the next crash.
        whenever(mockRemoteConfig.isAutocaptureExceptionsEnabled()).thenReturn(true)
        integration.onRemoteConfig(loaded = true)

        // Only the original install ever set the handler; no re-link.
        verify(mockAdapter, times(1)).setDefaultUncaughtExceptionHandler(integration)

        // Capturing is back on, and defaultExceptionHandler is still the original app handler.
        val thread = Thread.currentThread()
        val throwable = RuntimeException("crash after re-enable")
        integration.uncaughtException(thread, throwable)

        verify(mockPostHog).captureException(any<PostHogThrowable>(), anyOrNull())
        verify(mockExceptionHandler).uncaughtException(thread, throwable)

        // Clear the process-wide install flag so it doesn't leak into the next test.
        currentHandler = integration
        integration.uninstall()
    }

    @Test
    fun `a direct re-install on the owning instance honors the remote kill-switch`() {
        val integration = installedThenOverlaidAndRemoteDisabled()

        // A direct install() (e.g. re-init re-running config.integrations) must NOT resume
        // capture while the fetched/cached remote config still disables autocapture.
        integration.install(mockPostHog)

        val thread = Thread.currentThread()
        val throwable = RuntimeException("crash while remote-disabled")
        integration.uncaughtException(thread, throwable)

        verify(mockPostHog, never()).captureException(any<PostHogThrowable>(), anyOrNull())
        verify(mockExceptionHandler).uncaughtException(thread, throwable)

        // Clear the process-wide install flag so it doesn't leak into the next test.
        currentHandler = integration
        integration.uninstall()
    }

    @Test
    fun `a fresh instance takes over autocapture after the owner goes dormant on close`() {
        // I1 installs by default, a third-party handler overlays on top, then the client closes:
        // I1 can't unlink so it goes dormant. A later setup() must let a fresh instance take over
        // autocapture instead of being permanently blocked by the still-held process-wide flag.
        whenever(mockConfig.remoteConfigHolder).thenReturn(mockRemoteConfig)
        whenever(mockRemoteConfig.isAutocaptureExceptionsEnabled()).thenReturn(true)
        currentHandler = mockExceptionHandler

        val first = getSut()
        first.install(mockPostHog)

        // Third-party overlay grabs the top slot, keeping `first` as its delegate.
        val overlay = mock<Thread.UncaughtExceptionHandler>()
        currentHandler = overlay

        // Client closes: uninstall() can't unlink (overlay on top), so `first` goes dormant.
        first.uninstall()

        // Fresh setup(): a new instance must actually install on top of the overlay and capture.
        val second = getSut()
        second.install(mockPostHog)

        verify(mockAdapter).setDefaultUncaughtExceptionHandler(second)

        val thread = Thread.currentThread()
        val throwable = RuntimeException("crash after re-setup")
        second.uncaughtException(thread, throwable)

        verify(mockPostHog).captureException(any<PostHogThrowable>(), anyOrNull())
        verify(overlay).uncaughtException(thread, throwable)

        currentHandler = second
        second.uninstall()
    }

    @Test
    fun `onRemoteConfig keeps default install when remote config fetch fails`() {
        // Offline / failed first-launch fetch (loaded = false) must not tear down the default install.
        whenever(mockConfig.remoteConfigHolder).thenReturn(mockRemoteConfig)
        whenever(mockRemoteConfig.hasRemoteConfigFetched()).thenReturn(false)
        whenever(mockRemoteConfig.hasCachedErrorTrackingConfig()).thenReturn(false)
        whenever(mockRemoteConfig.isAutocaptureExceptionsEnabled()).thenReturn(false)

        val integration = getSut()
        integration.install(mockPostHog)

        verify(mockAdapter, times(1)).setDefaultUncaughtExceptionHandler(integration)

        integration.onRemoteConfig(loaded = false)

        // No uninstall: the handler is still ours, so setDefault isn't called again
        verify(mockAdapter, times(1)).setDefaultUncaughtExceptionHandler(any())

        integration.uninstall()
    }

    private class RecordingTarget : PostHogErrorTrackingAutoCaptureIntegration.CaptureTarget {
        val captured = mutableListOf<Throwable>()
        var flushCount = 0

        override fun capture(throwable: Throwable) {
            captured.add(throwable)
        }

        override fun flush() {
            flushCount++
        }
    }

    @Test
    fun `uncaughtException reproduces the JVM default crash output when no previous handler exists`() {
        currentHandler = null

        val target = RecordingTarget()
        val integration = PostHogErrorTrackingAutoCaptureIntegration(mockConfig, mockAdapter) { true }
        integration.installWith(target)

        val originalErr = System.err
        val stderr = java.io.ByteArrayOutputStream()
        System.setErr(java.io.PrintStream(stderr))
        try {
            integration.uncaughtException(Thread.currentThread(), RuntimeException("printed crash"))
        } finally {
            System.setErr(originalErr)
        }

        val output = stderr.toString()
        // Installing capture must not hide the crash from stderr: with no previous handler to
        // chain to, the integration prints what ThreadGroup's default behavior would have.
        assertEquals(true, output.contains("Exception in thread"), "Expected the default crash banner, got: $output")
        assertEquals(true, output.contains("printed crash"), "Expected the throwable in stderr, got: $output")

        integration.uninstall()
    }

    @Test
    fun `uncaughtException captures and flushes for a fresh throwable`() {
        val target = RecordingTarget()
        val integration = PostHogErrorTrackingAutoCaptureIntegration(mockConfig, mockAdapter) { true }
        integration.installWith(target)

        integration.uncaughtException(Thread.currentThread(), RuntimeException("fresh"))

        assertEquals(1, target.captured.size, "A first-seen throwable must be captured")
        assertEquals(1, target.flushCount)

        integration.uninstall()
    }

    @Test
    fun `uninstall by a non-installing instance does not tear down the installed handler`() {
        // First instance installs and owns the global handler.
        currentHandler = mockExceptionHandler
        val first = PostHogErrorTrackingAutoCaptureIntegration(mockConfig, mockAdapter) { true }
        first.installWith(RecordingTarget())
        verify(mockAdapter).setDefaultUncaughtExceptionHandler(first)

        // Second instance's install is a process-wide no-op (a handler is already installed).
        val second = PostHogErrorTrackingAutoCaptureIntegration(mockConfig, mockAdapter) { true }
        second.installWith(RecordingTarget())

        // Closing the second must NOT restore/replace the handler — it never installed.
        second.uninstall()
        verify(mockAdapter, never()).setDefaultUncaughtExceptionHandler(mockExceptionHandler)

        // The first still owns it and can restore on its own uninstall.
        first.uninstall()
        verify(mockAdapter).setDefaultUncaughtExceptionHandler(mockExceptionHandler)
    }
}
