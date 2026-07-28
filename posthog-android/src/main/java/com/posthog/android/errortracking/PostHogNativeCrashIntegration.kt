package com.posthog.android.errortracking

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.posthog.PostHogEventName
import com.posthog.PostHogIntegration
import com.posthog.PostHogInterface
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.internal.errortracking.NativeCrashEventCoercer
import com.posthog.android.internal.errortracking.TombstoneParser
import java.util.Date

/**
 * Captures native (NDK) crashes from previous runs of the app.
 *
 * A native signal (e.g. SIGSEGV) kills the process before any JVM handler
 * runs, so nothing can be captured in-process. Instead, on startup this
 * integration reads the crash records the OS kept via
 * [ActivityManager.getHistoricalProcessExitReasons], parses the attached
 * tombstone, and captures an `$exception` event with raw native stack frames
 * and the `$debug_images` needed for server-side symbolication against
 * uploaded `.so` debug symbols.
 *
 * Requires Android 12 (API 31), where native crash records carry a tombstone.
 * Enable with `errorTrackingConfig.captureNativeCrashes`; error tracking
 * autocapture must also be enabled in the project settings.
 */
public class PostHogNativeCrashIntegration(
    private val context: Context,
    private val config: PostHogAndroidConfig,
) : PostHogIntegration {
    private var postHog: PostHogInterface? = null

    private companion object {
        private const val LAST_CAPTURED_TIMESTAMP_KEY = "nativeCrashLastCapturedTimestamp"
        private const val MAX_EXIT_RECORDS = 20

        @Volatile
        private var integrationInstalled = false
    }

    override fun install(postHog: PostHogInterface) {
        this.postHog = postHog

        if (integrationInstalled || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return
        }
        if (config.remoteConfigHolder?.isNativeCrashCaptureEnabled() != true) {
            return
        }
        // While the store is unreadable (Direct Boot) the watermark reads as
        // absent, which would re-capture already-reported crashes.
        if (config.cachePreferences?.isAvailable() == false) {
            return
        }
        integrationInstalled = true

        Thread({ scanSafely(postHog) }, "PostHogNativeCrashScanner")
            .apply { isDaemon = true }
            .start()
    }

    override fun uninstall() {
        integrationInstalled = false
    }

    override fun onRemoteConfig(loaded: Boolean) {
        // Only react to a live config; a failed attempt applies no fresh values.
        if (!loaded) {
            return
        }
        if (config.remoteConfigHolder?.isNativeCrashCaptureEnabled() == true) {
            postHog?.let { install(it) }
        }
    }

    private fun scanSafely(postHog: PostHogInterface) {
        try {
            scan(postHog)
        } catch (e: Throwable) {
            config.logger.log("Native crash scan failed: $e.")
        }
    }

    private fun scan(postHog: PostHogInterface) {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val preferences = config.cachePreferences ?: return

        val watermark = preferences.getValue(LAST_CAPTURED_TIMESTAMP_KEY) as? Long ?: 0L
        val crashes =
            activityManager
                .getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_RECORDS)
                .filter { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && it.timestamp > watermark }
                // oldest first, so events arrive in crash order
                .sortedBy { it.timestamp }

        if (crashes.isEmpty()) {
            return
        }

        val parser = TombstoneParser()
        val coercer = NativeCrashEventCoercer()

        crashes.forEach { exitInfo ->
            val properties =
                try {
                    exitInfo.traceInputStream?.use { stream ->
                        coercer.toPostHogProperties(parser.parse(stream))
                    }
                } catch (e: Throwable) {
                    config.logger.log("Tombstone parsing failed: $e.")
                    null
                }

            properties?.let {
                postHog.capture(
                    PostHogEventName.EXCEPTION.event,
                    properties = it,
                    timestamp = Date(exitInfo.timestamp),
                )
            }

            // Advance per record — unparsable ones too, retrying can't succeed —
            // so dying mid-scan never re-captures already-reported crashes.
            preferences.setValue(LAST_CAPTURED_TIMESTAMP_KEY, exitInfo.timestamp)
        }

        config.logger.log("Captured ${crashes.size} native crash record(s) from previous runs.")
    }
}
