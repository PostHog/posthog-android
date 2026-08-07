package com.posthog.android.errortracking

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.PostHogInterface
import com.posthog.android.API_KEY
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.internal.errortracking.NativeCrashWatermarkStore
import com.posthog.android.internal.errortracking.TestProtoWriter
import com.posthog.internal.PostHogRemoteConfig
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager
import org.robolectric.shadows.ShadowApplication
import java.io.ByteArrayInputStream
import java.util.Date
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
internal class PostHogNativeCrashIntegrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val postHog = mock<PostHogInterface>()
    private lateinit var config: PostHogAndroidConfig
    private val installed = mutableListOf<PostHogNativeCrashIntegration>()

    // Runs submitted work inline so scans complete during install()
    private open class DirectExecutorService : AbstractExecutorService() {
        private var shutdown = false

        override fun execute(command: Runnable) = command.run()

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdown = true
            return mutableListOf()
        }

        override fun isShutdown(): Boolean = shutdown

        override fun isTerminated(): Boolean = shutdown

        override fun awaitTermination(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = true
    }

    // Counts submissions without running them, to observe scanner scheduling
    private class RecordingExecutorService : DirectExecutorService() {
        var submitted = 0

        override fun execute(command: Runnable) {
            submitted++
        }
    }

    @Before
    fun setUp() {
        config =
            PostHogAndroidConfig(API_KEY).apply {
                errorTrackingConfig.captureNativeCrashes = true
                remoteConfigHolder =
                    mock<PostHogRemoteConfig> {
                        on { isNativeCrashCaptureEnabled() } doReturn true
                    }
            }
    }

    @After
    fun tearDown() {
        // release the process-wide scanner guard between tests
        installed.forEach { it.uninstall() }
        installed.clear()
        // static process name survives across tests
        ShadowApplication.setProcessName(context.packageName)
    }

    private fun install(executor: DirectExecutorService = DirectExecutorService()): PostHogNativeCrashIntegration {
        val integration = PostHogNativeCrashIntegration(context, config, { executor })
        installed.add(integration)
        integration.install(postHog)
        return integration
    }

    private fun addExitRecord(
        reason: Int,
        timestamp: Long,
        pid: Int = 1,
        trace: ByteArray? = null,
    ) {
        val exitInfo =
            ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                .setReason(reason)
                .setTimestamp(timestamp)
                .setPid(pid)
                .apply { trace?.let { setTraceInputStream(ByteArrayInputStream(it)) } }
                .build()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(activityManager).addApplicationExitInfo(exitInfo)
    }

    private fun tombstoneBytes(): ByteArray =
        TestProtoWriter()
            .varint(6, 1) // tid
            .message(16) {
                varint(1, 1)
                message(2) {
                    varint(1, 1)
                    message(4) {
                        varint(1, 0x10) // rel_pc
                        varint(2, 0x2010) // pc
                        string(4, "crash_here")
                        string(6, "/data/app/libengine.so")
                    }
                }
            }
            .toByteArray()

    private fun watermark(): Long = NativeCrashWatermarkStore(context).get()

    @Test
    fun `captures native crashes and ignores other exit reasons`() {
        addExitRecord(ApplicationExitInfo.REASON_ANR, timestamp = 100)
        addExitRecord(ApplicationExitInfo.REASON_CRASH_NATIVE, timestamp = 200, trace = tombstoneBytes())

        install()

        verify(postHog, times(1)).capture(
            eq("\$exception"),
            anyOrNull(),
            any(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            eq(Date(200)),
        )
        assertEquals(200, watermark())
    }

    @Test
    fun `already acknowledged records are not reprocessed`() {
        NativeCrashWatermarkStore(context).advance(200)
        addExitRecord(ApplicationExitInfo.REASON_CRASH_NATIVE, timestamp = 200, trace = tombstoneBytes())

        install()

        verify(postHog, never()).capture(
            any(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
        )
        assertEquals(200, watermark())
    }

    @Test
    fun `records without a tombstone advance the watermark without capturing`() {
        addExitRecord(ApplicationExitInfo.REASON_CRASH_NATIVE, timestamp = 300)

        install()

        verify(postHog, never()).capture(
            any(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
        )
        assertEquals(300, watermark())
    }

    @Test
    fun `a failed capture keeps tied records unacknowledged for the next scan`() {
        addExitRecord(ApplicationExitInfo.REASON_CRASH_NATIVE, timestamp = 400, pid = 1, trace = tombstoneBytes())
        addExitRecord(ApplicationExitInfo.REASON_CRASH_NATIVE, timestamp = 400, pid = 2, trace = tombstoneBytes())
        doNothing()
            .doThrow(RuntimeException("queue rejected"))
            .whenever(postHog)
            .capture(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())

        install()

        // both records were attempted, and since the second capture of the
        // tied group failed, the group stays unacknowledged so the next scan
        // retries it instead of losing the failed record forever
        verify(postHog, times(2)).capture(
            any(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
        )
        assertEquals(0, watermark())
    }

    @Test
    fun `only one scanner owns the process-wide guard`() {
        val owner = RecordingExecutorService()
        val second = RecordingExecutorService()
        val third = RecordingExecutorService()
        val fourth = RecordingExecutorService()

        val ownerIntegration = install(owner)
        assertEquals(1, owner.submitted)

        val secondIntegration = install(second)
        assertEquals(0, second.submitted)

        // a non-owner uninstall must not release the guard
        secondIntegration.uninstall()
        install(third)
        assertEquals(0, third.submitted)

        // the owner releasing it lets a new scanner start
        ownerIntegration.uninstall()
        install(fourth)
        assertEquals(1, fourth.submitted)
    }

    @Test
    fun `a transient read failure aborts the scan without acknowledging`() {
        addExitRecord(ApplicationExitInfo.REASON_CRASH_NATIVE, timestamp = 500, trace = tombstoneBytes())
        val exitInfo =
            ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                .setReason(ApplicationExitInfo.REASON_CRASH_NATIVE)
                .setTimestamp(600)
                .setPid(2)
                .setTraceInputStream(
                    object : java.io.InputStream() {
                        override fun read(): Int = throw java.io.IOException("transient")
                    },
                )
                .build()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(activityManager).addApplicationExitInfo(exitInfo)

        install()

        // the readable record was captured and acknowledged; the unreadable
        // one aborted the scan so the next launch retries it
        verify(postHog, times(1)).capture(
            any(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
        )
        assertEquals(500, watermark())
    }

    @Test
    fun `a tombstone that does not parse is acknowledged and skipped`() {
        addExitRecord(
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            timestamp = 700,
            trace = byteArrayOf(0x7f, 0x7f, 0x7f, 0x7f, 0x7f, 0x7f, 0x7f, 0x7f, 0x7f, 0x7f),
        )

        install()

        verify(postHog, never()).capture(
            any(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
        )
        // a deterministic parse failure must not hold the watermark below the
        // record forever, or every newer crash behind it would be blocked
        assertEquals(700, watermark())
    }

    @Test
    fun `a remote disable releases the scanner for a later re-enable`() {
        val first = RecordingExecutorService()
        val second = RecordingExecutorService()
        install(first)
        assertEquals(1, first.submitted)

        whenever(config.remoteConfigHolder!!.isNativeCrashCaptureEnabled()).doReturn(false)
        installed.first().onRemoteConfig(loaded = true)

        whenever(config.remoteConfigHolder!!.isNativeCrashCaptureEnabled()).doReturn(true)
        install(second)
        assertEquals(1, second.submitted)
    }

    @Test
    fun `scans in a renamed default application process`() {
        // android:process on <application> renames the default process away
        // from the package name; the scanner must still run there
        context.applicationInfo.processName = "${context.packageName}:app"
        ShadowApplication.setProcessName("${context.packageName}:app")
        addExitRecord(ApplicationExitInfo.REASON_CRASH_NATIVE, timestamp = 800, trace = tombstoneBytes())

        install()

        verify(postHog, times(1)).capture(
            any(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
        )
        assertEquals(800, watermark())
    }

    @Test
    fun `does not scan outside the default application process`() {
        ShadowApplication.setProcessName("${context.packageName}:worker")
        val executor = RecordingExecutorService()

        install(executor)

        assertEquals(0, executor.submitted)
    }

    @Test
    fun `does not scan when the remote toggle is off`() {
        whenever(config.remoteConfigHolder!!.isNativeCrashCaptureEnabled()).doReturn(false)
        val executor = RecordingExecutorService()

        install(executor)

        assertEquals(0, executor.submitted)
    }
}
