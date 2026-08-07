package com.posthog.android.errortracking

import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.UserManager
import androidx.annotation.RequiresApi
import com.posthog.PostHogEventName
import com.posthog.PostHogIntegration
import com.posthog.PostHogInterface
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.internal.errortracking.NativeCrashEventCoercer
import com.posthog.android.internal.errortracking.NativeCrashWatermarkStore
import com.posthog.android.internal.errortracking.TombstoneParser
import com.posthog.android.internal.getActivityManager
import com.posthog.internal.PostHogThreadFactory
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures native (NDK) crashes from previous runs of the app.
 *
 * A native signal (e.g. SIGSEGV) kills the process before any JVM handler
 * runs, so nothing can be captured in-process. Instead, on startup this
 * integration reads the crash records the OS kept via
 * [android.app.ActivityManager.getHistoricalProcessExitReasons], parses the
 * attached tombstone, and captures an `$exception` event with raw native stack
 * frames and the `$debug_images` needed for server-side symbolication against
 * uploaded `.so` debug symbols.
 *
 * Requires Android 12 (API 31), where native crash records carry a tombstone.
 * Enable with `errorTrackingConfig.captureNativeCrashes`; error tracking
 * autocapture must also be enabled in the project settings.
 */
public class PostHogNativeCrashIntegration : PostHogIntegration {
    private val context: Context
    private val config: PostHogAndroidConfig
    private val executorFactory: () -> ExecutorService
    private var executor: ExecutorService? = null
    private var postHog: PostHogInterface? = null

    // Whether this instance owns the process-wide scanner guard, so only the
    // owner can release it on uninstall.
    @Volatile
    private var installedByThisInstance = false

    public constructor(context: Context, config: PostHogAndroidConfig) : this(
        context,
        config,
        { Executors.newSingleThreadExecutor(PostHogThreadFactory("PostHogNativeCrashThread")) },
    )

    internal constructor(context: Context, config: PostHogAndroidConfig, executorFactory: () -> ExecutorService) {
        this.context = context
        this.config = config
        this.executorFactory = executorFactory
    }

    private companion object {
        // One scanner per process: concurrent scanners would race the watermark
        // and capture the same crash records twice.
        private val integrationInstalled = AtomicBoolean(false)
    }

    override fun install(postHog: PostHogInterface) {
        this.postHog = postHog

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return
        }
        if (config.remoteConfigHolder?.isNativeCrashCaptureEnabled() != true) {
            return
        }
        // The exit history spans every process of the package, and the scanner
        // guard and watermark are process-local, so scanning from more than
        // one process would capture the same records twice. The main process
        // scans on behalf of all of them.
        if (Application.getProcessName() != context.packageName) {
            return
        }
        // While the device is locked (Direct Boot) the watermark store is
        // unreadable and scanning would re-capture already-reported crashes.
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        if (userManager?.isUserUnlocked == false) {
            return
        }
        if (!integrationInstalled.compareAndSet(false, true)) {
            return
        }
        installedByThisInstance = true

        try {
            // A fresh executor per acquisition, so a scan cancelled by
            // uninstall or a remote disable can be scheduled again later.
            val executor = executorFactory()
            this.executor = executor
            executor.submit { scanSafely(postHog) }
        } catch (e: Throwable) {
            config.logger.log("Native crash scan could not be scheduled: $e.")
            installedByThisInstance = false
            integrationInstalled.set(false)
        }
    }

    override fun uninstall() {
        if (!installedByThisInstance) {
            return
        }
        // Stop the scanner before releasing the process-wide guard, so a new
        // install cannot start a second scanner while this one is still running.
        executor?.shutdownNow()
        try {
            executor?.awaitTermination(1, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        installedByThisInstance = false
        integrationInstalled.set(false)
    }

    override fun onRemoteConfig(loaded: Boolean) {
        // Only react to a live config; a failed attempt applies no fresh values.
        if (!loaded) {
            return
        }
        if (config.remoteConfigHolder?.isNativeCrashCaptureEnabled() == true) {
            postHog?.let { install(it) }
        } else {
            // A live kill switch also aborts an in-flight scan; a later
            // re-enable schedules a fresh scanner.
            uninstall()
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun scanSafely(postHog: PostHogInterface) {
        try {
            scan(postHog)
        } catch (e: Throwable) {
            config.logger.log("Native crash scan failed: $e.")
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun scan(postHog: PostHogInterface) {
        val activityManager = getActivityManager(context) ?: return
        val watermarkStore = NativeCrashWatermarkStore(context)
        val watermark = watermarkStore.get()

        // maxNum 0 returns every retained record. A positive cap is applied
        // before the reason filter, so enough newer non-crash exits would
        // starve an older native crash out of every scan.
        val crashes =
            activityManager
                .getHistoricalProcessExitReasons(context.packageName, 0, 0)
                .filter { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && it.timestamp > watermark }
                // oldest first, so events arrive in crash order
                .sortedBy { it.timestamp }

        if (crashes.isEmpty()) {
            return
        }

        val parser = TombstoneParser()
        val applicationInfo = context.applicationInfo
        val coercer =
            NativeCrashEventCoercer(
                inAppPathPrefixes =
                    listOfNotNull(
                        applicationInfo.nativeLibraryDir,
                        applicationInfo.sourceDir,
                        applicationInfo.dataDir,
                    ) + (applicationInfo.splitSourceDirs?.toList() ?: emptyList()),
            )
        var captured = 0

        for ((index, exitInfo) in crashes.withIndex()) {
            // uninstall interrupts the scanner; stop before acknowledging more records
            if (Thread.currentThread().isInterrupted) {
                return
            }

            // Reading the trace can fail transiently (I/O), so abort without
            // acknowledging and retry on the next launch. A tombstone that
            // read fully but does not parse is deterministic: acknowledge and
            // skip it, because retrying forever would hold the watermark below
            // it and block every newer crash behind it.
            val tombstoneBytes =
                try {
                    exitInfo.traceInputStream?.use { stream -> stream.readBytes() }
                } catch (e: Throwable) {
                    config.logger.log("Reading a tombstone failed, retrying on the next launch: $e.")
                    return
                }

            val properties =
                try {
                    tombstoneBytes?.let { coercer.toPostHogProperties(parser.parse(it)) }
                } catch (e: Throwable) {
                    config.logger.log("Skipping a tombstone that could not be parsed: $e.")
                    null
                }

            properties?.let {
                postHog.capture(
                    PostHogEventName.EXCEPTION.event,
                    properties = it,
                    timestamp = Date(exitInfo.timestamp),
                )
                captured++
            }

            // Advance only after capture returned, and only past the last
            // record of each distinct timestamp: at-least-once. Losing a crash
            // to an unacknowledged queue is worse than a rare duplicate from
            // dying between the capture and this synchronous write, and
            // sibling processes can die in the same millisecond, so advancing
            // on the first record of a tie would orphan the rest if the scan
            // dies mid-group.
            val lastOfItsTimestamp =
                index == crashes.lastIndex || crashes[index + 1].timestamp != exitInfo.timestamp
            if (lastOfItsTimestamp) {
                watermarkStore.advance(exitInfo.timestamp)
            }
        }

        config.logger.log(
            "Captured $captured native crash record(s) from previous runs" +
                " (${crashes.size - captured} record(s) skipped without a readable tombstone).",
        )
    }
}
