package com.posthog.server.internal

import com.posthog.PostHogConfig
import com.posthog.errortracking.PostHogErrorTrackingAutoCaptureIntegration
import com.posthog.server.PostHogInterface

/**
 * Wires the shared uncaught-exception integration to the server client, so the client class itself
 * carries none of the crash-capture mechanics: the gate (purely the local flag — the server SDK
 * never fetches remote config, so the remote-config gate the Android SDK uses can never fire), the
 * per-thread fatal policy, and the capture target.
 */
internal object PostHogUncaughtExceptionCapture {
    // Event-level capture-integration identity for the uncaught handler, following the
    // sdk-specs lowercase <technology>.<stable_hook> convention.
    private const val EXCEPTION_SOURCE_ATTRIBUTE = "\$exception_source"
    private const val EXCEPTION_SOURCE_UNCAUGHT_HANDLER = "jvm.uncaught_exception_handler"

    /**
     * Installs the handler delivering captures to [client]. Returns the integration to uninstall
     * on close, or null when installation failed — an environment that refuses a process-wide
     * handler (e.g. a SecurityManager) must not fail client setup.
     */
    fun install(
        client: PostHogInterface,
        coreConfig: PostHogConfig,
    ): PostHogErrorTrackingAutoCaptureIntegration? {
        val integration =
            PostHogErrorTrackingAutoCaptureIntegration(coreConfig, { true }, ::isProcessFatal)
        // The uncaught Throwable is a PostHogThrowable carrying fatal/handled=false/mechanism;
        // routing it through captureException preserves those via the shared coercer. A
        // fatal-level event takes PostHogMemoryQueue's bounded blocking fatal path inside
        // add() (same fatal-record marker the core queue keys on), so capture() itself
        // delivers the crash — and everything queued ahead of it — before returning.
        val target =
            object : PostHogErrorTrackingAutoCaptureIntegration.CaptureTarget {
                override fun capture(throwable: Throwable) {
                    // $exception_source names the concrete runtime hook per the sdk-specs
                    // convention (<technology>.<stable_hook>); the mechanism category
                    // (onuncaughtexception) rides on the PostHogThrowable.
                    client.captureException(
                        throwable,
                        null,
                        mapOf(EXCEPTION_SOURCE_ATTRIBUTE to EXCEPTION_SOURCE_UNCAUGHT_HANDLER),
                    )
                }

                override fun flush() {
                    // Deliberately empty. The fatal path above already drains the queue
                    // within its bounded budget; the public flush() would run another HTTP
                    // batch inline on the crashing thread with no timeout (e.g. retrying a
                    // batch a 5xx just requeued), stalling crash delegation past the
                    // advertised bound. A worker-thread (non-fatal) capture leaves the
                    // process alive, so the periodic flush delivers it.
                }
            }

        return try {
            integration.installWith(target)
            integration
        } catch (e: Throwable) {
            // Thread.setDefaultUncaughtExceptionHandler can throw (SecurityException under a
            // SecurityManager); the integration rolls its ownership state back, and setup
            // continues with capture disabled instead of leaving a half-initialized client.
            coreConfig.logger.log("Could not install the uncaught-exception handler: $e.")
            null
        }
    }

    // The JVM cannot tell whether a thread's death will end the process, so this approximates the
    // spec's "expected to terminate" boundary with the main thread: an uncaught exception there is
    // fatal, while a worker thread's kills only that thread (level error) and the process lives on.
    // Id 1 is the initial thread on mainstream JVMs and "main" its conventional name; either match
    // counts, since a missed main thread would silently downgrade a real crash. Known approximation
    // limits, accepted rather than censusing live threads inside a crash handler: a worker that
    // happens to be the last live non-daemon thread does end the process (its crash is still level
    // error, async delivery), and a main-thread exception need not end it while other non-daemon
    // threads keep running.
    @Suppress("DEPRECATION") // Thread.getId is deprecated on JDK 19+ but stable while a thread lives
    private fun isProcessFatal(thread: Thread): Boolean = thread.id == 1L || thread.name == "main"
}
