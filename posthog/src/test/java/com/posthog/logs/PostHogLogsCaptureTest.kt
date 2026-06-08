package com.posthog.logs

import com.posthog.API_KEY
import com.posthog.PostHog
import com.posthog.PostHogConfig
import com.posthog.PostHogInterface
import com.posthog.TestPostHogContext
import com.posthog.internal.PostHogThreadFactory
import com.posthog.mockHttp
import com.posthog.unGzip
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class PostHogLogsCaptureTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private val queueExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestQueue"))
    private val replayQueueExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestReplayQueue"))
    private val logsExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestLogsQueue"))
    private val remoteConfigExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestRemoteConfig"))
    private val cachedEventsExecutor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("TestCachedEvents"))

    private fun getSut(
        http: MockWebServer,
        configure: PostHogConfig.() -> Unit = {},
    ): PostHogInterface {
        val storagePrefix = tmpDir.newFolder().absolutePath
        val config =
            PostHogConfig(API_KEY, http.url("/").toString()).apply {
                this.storagePrefix = File(storagePrefix, "events").absolutePath
                this.replayStoragePrefix = File(storagePrefix, "snapshots").absolutePath
                this.logsStoragePrefix = File(storagePrefix, "logs").absolutePath
                this.flushAt = 1
                this.logs.flushAt = 1
                this.context = TestPostHogContext()
                configure()
            }
        return PostHog.withInternal(
            config,
            queueExecutor,
            replayQueueExecutor,
            remoteConfigExecutor,
            cachedEventsExecutor,
            reloadFeatureFlags = false,
            logsExecutor = logsExecutor,
        )
    }

    @AfterTest
    fun cleanup() {
        tmpDir.root.deleteRecursively()
    }

    @Test
    fun `logger info posts to slash i v1 logs with OTLP body`() {
        val http = mockHttp()
        val sut = getSut(http)

        sut.logger.info("hello logs", mapOf("source" to "test"))

        // Wait for the executors to drain the queue and ship the batch.
        logsExecutor.shutdown()
        logsExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)

        val request = http.takeRequest()
        assertTrue(request.path!!.startsWith("/i/v1/logs?token="), "path was ${request.path}")
        val unzipped = request.body.unGzip()
        assertTrue(unzipped.contains("\"stringValue\":\"hello logs\""), unzipped)
        // info → severityNumber 9
        assertTrue(unzipped.contains("\"severityNumber\":9"), unzipped)
        assertTrue(unzipped.contains("\"severityText\":\"info\""), unzipped)
        // User attribute is on the record
        assertTrue(unzipped.contains("\"key\":\"source\""), unzipped)
        // Resource attributes carry the service.name fallback + os.* from context
        assertTrue(unzipped.contains("\"key\":\"service.name\""), unzipped)
        assertTrue(unzipped.contains("\"key\":\"os.name\""), unzipped)

        sut.close()
        http.shutdown()
    }

    @Test
    fun `captureLog emits traceId spanId and flags on the wire`() {
        val http = mockHttp()
        val sut = getSut(http)

        sut.captureLog(
            "traced log",
            severity = PostHogLogSeverity.ERROR,
            attributes = mapOf("code" to "PAY_3001"),
            traceId = "4bf92f3577b34da6a3ce929d0e0e4736",
            spanId = "00f067aa0ba902b7",
            traceFlags = 1,
        )

        logsExecutor.shutdown()
        logsExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)

        val unzipped = http.takeRequest().body.unGzip()
        // trace_flags is renamed to `flags` on the OTLP wire.
        assertTrue(unzipped.contains("\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\""), unzipped)
        assertTrue(unzipped.contains("\"spanId\":\"00f067aa0ba902b7\""), unzipped)
        assertTrue(unzipped.contains("\"flags\":1"), unzipped)
        // error → severityNumber 17
        assertTrue(unzipped.contains("\"severityNumber\":17"), unzipped)

        sut.close()
        http.shutdown()
    }

    @Test
    fun `captureLog with explicit traceFlags zero still emits flags 0`() {
        val http = mockHttp()
        val sut = getSut(http)

        // 0x00 is a valid W3C trace-flags value (sampled = false). It must be
        // emitted, not treated as "absent" — guards the null-vs-zero trap on
        // the three `traceFlags?.let` sites.
        sut.captureLog("zero flags", traceFlags = 0)

        logsExecutor.shutdown()
        logsExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)

        val unzipped = http.takeRequest().body.unGzip()
        assertTrue(unzipped.contains("\"flags\":0"), unzipped)

        sut.close()
        http.shutdown()
    }

    @Test
    fun `captureLog without trace fields omits traceId spanId and flags`() {
        val http = mockHttp()
        val sut = getSut(http)

        sut.captureLog("plain log")

        logsExecutor.shutdown()
        logsExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)

        val unzipped = http.takeRequest().body.unGzip()
        assertEquals(false, unzipped.contains("\"traceId\""), unzipped)
        assertEquals(false, unzipped.contains("\"spanId\""), unzipped)
        assertEquals(false, unzipped.contains("\"flags\""), unzipped)
        // missing level defaults to info → severityNumber 9
        assertTrue(unzipped.contains("\"severityNumber\":9"), unzipped)

        sut.close()
        http.shutdown()
    }

    @Test
    fun `logger info with blank message is a no-op`() {
        val http = mockHttp()
        val sut = getSut(http)

        sut.logger.info("   ")
        sut.logger.info("")
        sut.logger.info("\t")

        Thread.sleep(150)
        assertEquals(0, http.requestCount)

        sut.close()
        http.shutdown()
    }

    @Test
    fun `optOut suppresses log capture`() {
        val http = mockHttp()
        val sut = getSut(http) { optOut = true }

        sut.logger.error("should not ship")

        Thread.sleep(150)
        assertEquals(0, http.requestCount)

        sut.close()
        http.shutdown()
    }

    @Test
    fun `beforeSend hook can mutate record before enqueue`() {
        val http = mockHttp()
        val sut =
            getSut(http) {
                logs.addBeforeSend { record ->
                    record.copy(body = "redacted")
                }
            }

        sut.logger.info("secret token: abc")

        logsExecutor.shutdown()
        logsExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)

        val unzipped = http.takeRequest().body.unGzip()
        assertTrue(unzipped.contains("\"stringValue\":\"redacted\""), unzipped)
        assertEquals(false, unzipped.contains("secret token"), "raw body leaked: $unzipped")

        sut.close()
        http.shutdown()
    }

    @Test
    fun `beforeSend returning null drops the record`() {
        val http = mockHttp()
        val sut =
            getSut(http) {
                logs.addBeforeSend { null }
            }

        sut.logger.warn("never ships")

        Thread.sleep(150)
        assertEquals(0, http.requestCount)

        sut.close()
        http.shutdown()
    }

    @Test
    fun `rate cap drops records past the per-window budget`() {
        val http = mockHttp(total = 5)
        val sut =
            getSut(http) {
                logs.rateCapMaxLogs = 3
                logs.rateCapWindowSeconds = 60 // wide so test fits in window
            }

        // 5 calls, cap is 3 → 3 should ship, 2 should drop.
        repeat(5) { sut.logger.info("msg $it") }

        logsExecutor.shutdown()
        logsExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)

        assertEquals(3, http.requestCount)

        sut.close()
        http.shutdown()
    }

    @Test
    fun `rate cap of zero disables the cap`() {
        val http = mockHttp(total = 10)
        val sut =
            getSut(http) {
                logs.rateCapMaxLogs = 0
                logs.rateCapWindowSeconds = 60
            }

        repeat(10) { sut.logger.info("msg $it") }

        logsExecutor.shutdown()
        logsExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)

        assertEquals(10, http.requestCount)

        sut.close()
        http.shutdown()
    }

    @Test
    fun `logs serviceName overrides PostHogContext app_namespace`() {
        val http = mockHttp()
        val sut =
            getSut(http) {
                logs.serviceName = "user-override"
                logs.environment = "staging"
            }

        sut.logger.info("ping")

        logsExecutor.shutdown()
        logsExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)

        val unzipped = http.takeRequest().body.unGzip()
        // User-supplied serviceName wins over PostHogContext.$app_namespace.
        assertTrue(unzipped.contains("\"stringValue\":\"user-override\""), unzipped)
        assertTrue(unzipped.contains("\"key\":\"deployment.environment\""), unzipped)
        assertTrue(unzipped.contains("\"stringValue\":\"staging\""), unzipped)

        sut.close()
        http.shutdown()
    }
}
