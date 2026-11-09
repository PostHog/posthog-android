package com.posthog.android.errortracking

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.PostHog
import com.posthog.PostHogIntegration
import com.posthog.PostHogInterface
import com.posthog.android.PROJECT_TOKEN
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.internal.errortracking.TestProtoWriter
import com.posthog.internal.PostHogMemoryPreferences
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager
import org.robolectric.shadows.ShadowApplication
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
internal class PostHogNativeCrashConsentTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val tmp = File(context.cacheDir, "native-crash-consent-${System.nanoTime()}")
    private var client: PostHogInterface? = null
    private var remoteConfigApplied = CountDownLatch(1)

    @Before
    fun setUp() {
        server.start()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path.orEmpty().contains("/config")) {
                        MockResponse().setBody("""{"errorTracking":{"autocaptureExceptions":true}}""")
                    } else {
                        MockResponse().setBody("{}")
                    }
            }
    }

    @After
    fun tearDown() {
        client?.close()
        server.shutdown()
        tmp.deleteRecursively()
        ShadowApplication.setProcessName(context.packageName)
    }

    private fun tombstone(): ByteArray =
        TestProtoWriter()
            .varint(6, 1)
            .message(16) {
                varint(1, 1)
                message(2) {
                    varint(1, 1)
                    message(4) {
                        varint(1, 0x10)
                        varint(2, 0x2010)
                        string(4, "crash_here")
                        string(6, "/data/app/libengine.so")
                    }
                }
            }
            .toByteArray()

    private fun addNativeCrash(timestamp: Long) {
        val exitInfo =
            ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                .setReason(ApplicationExitInfo.REASON_CRASH_NATIVE)
                .setTimestamp(timestamp)
                .setPid(timestamp.toInt())
                .setTraceInputStream(ByteArrayInputStream(tombstone()))
                .build()
        shadowOf(context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).addApplicationExitInfo(exitInfo)
    }

    private fun setup(optOut: Boolean): PostHogInterface {
        val applied = CountDownLatch(1).also { remoteConfigApplied = it }
        val prefs = PostHogMemoryPreferences()
        prefs.setValue("errorTracking", mapOf("autocaptureExceptions" to true))
        val config =
            PostHogAndroidConfig(PROJECT_TOKEN, server.url("/").toString().trimEnd('/')).apply {
                flushAt = 1
                storagePrefix = File(tmp, "events").absolutePath
                replayStoragePrefix = File(tmp, "snapshots").absolutePath
                cachePreferences = prefs
                this.optOut = optOut
                persistOptOut = false
                preloadFeatureFlags = false
                errorTrackingConfig.autoCapture = true
                errorTrackingConfig.captureNativeCrashes = true
                addIntegration(PostHogNativeCrashIntegration(this@PostHogNativeCrashConsentTest.context, this, { DirectExecutorService() }))
                // added last, so it fires after the native crash integration handled the same config
                addIntegration(
                    object : PostHogIntegration {
                        override fun onRemoteConfig(loaded: Boolean) {
                            if (loaded) applied.countDown()
                        }
                    },
                )
            }
        return PostHog.with(config).also { client = it }
    }

    private fun exceptionEventsSent(waitMs: Long = 3000): Int {
        var count = 0
        while (true) {
            val request = server.takeRequest(waitMs, TimeUnit.MILLISECONDS) ?: return count
            val bytes = request.body.readByteArray()
            val body =
                if (request.getHeader(
                        "Content-Encoding",
                    ) == "gzip"
                ) {
                    GZIPInputStream(ByteArrayInputStream(bytes)).readBytes()
                } else {
                    bytes
                }
            count += Regex("\"event\"\\s*:\\s*\"\\\$exception\"").findAll(String(body)).count()
        }
    }

    // a /config response landing after close() can reinstall the scanner on the
    // closed client, so wait for it before relaunching
    private fun awaitRemoteConfigApplied() {
        assertTrue(remoteConfigApplied.await(5, TimeUnit.SECONDS), "remote config was not applied")
    }

    // interval bounds are inclusive, so step to a strictly later millisecond
    private fun awaitNextMillisecond(): Long {
        Thread.sleep(2)
        return System.currentTimeMillis()
    }

    @Test
    fun `a native crash recorded while opted in is sent`() {
        addNativeCrash(timestamp = System.currentTimeMillis())

        setup(optOut = false)

        assertEquals(1, exceptionEventsSent())
    }

    @Test
    fun `a native crash recorded during a launch that started opted out is not sent after optIn`() {
        setup(optOut = true).close()
        addNativeCrash(timestamp = System.currentTimeMillis())

        val posthog = setup(optOut = true)
        assertEquals(0, exceptionEventsSent(waitMs = 1000), "nothing may be sent while opted out")

        posthog.optIn()

        assertEquals(0, exceptionEventsSent(), "a crash recorded while opted out must not be sent after optIn")
    }

    @Test
    fun `a native crash recorded after a runtime optOut is not sent after optIn`() {
        addNativeCrash(timestamp = System.currentTimeMillis())
        val posthog = setup(optOut = false)
        assertEquals(1, exceptionEventsSent(), "the consented crash is sent")

        posthog.optOut()
        addNativeCrash(timestamp = System.currentTimeMillis())
        assertNull(server.takeRequest(500, TimeUnit.MILLISECONDS), "nothing sent while opted out")

        posthog.optIn()

        assertEquals(0, exceptionEventsSent(), "a crash recorded while opted out must not be sent after optIn")
    }

    @Test
    fun `a consented native crash survives a launch that starts opted out`() {
        val first = setup(optOut = false)
        awaitRemoteConfigApplied()
        first.close()
        addNativeCrash(timestamp = System.currentTimeMillis())
        awaitNextMillisecond()

        val posthog = setup(optOut = true)
        posthog.optIn()

        assertEquals(1, exceptionEventsSent(), "a crash recorded while opted in must be sent")
    }

    @Test
    fun `a native crash recorded after optIn is sent`() {
        val posthog = setup(optOut = false)
        awaitRemoteConfigApplied()
        posthog.optOut()
        posthog.optIn()
        posthog.close()

        addNativeCrash(timestamp = awaitNextMillisecond())
        setup(optOut = false)

        assertEquals(1, exceptionEventsSent(), "a crash recorded after optIn must be sent")
    }
}
