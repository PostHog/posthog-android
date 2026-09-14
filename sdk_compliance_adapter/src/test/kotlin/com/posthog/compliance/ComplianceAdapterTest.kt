package com.posthog.compliance

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComplianceAdapterTest {
    private val http = OkHttpClient()

    private fun fixture(name: String): String = javaClass.getResource("/json/$name")!!.readText()

    private fun action(
        name: String,
        body: String = "{}",
    ): JsonObject =
        http.newCall(
            Request.Builder().url("http://127.0.0.1:18293/$name")
                .post(body.toRequestBody("application/json".toMediaType())).build(),
        ).execute().use {
            check(it.isSuccessful) { it.body?.string().orEmpty() }
            JsonParser.parseString(it.body!!.string()).asJsonObject
        }

    private fun withAdapter(
        profile: SdkProfile,
        closeTimeoutMs: Long = 15_000,
        distinctId: String? = null,
        test: (MockWebServer) -> Unit,
    ) {
        val mock = MockWebServer()
        mock.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setHeader("Content-Type", "application/json").setBody(
                        if (request.path!!.startsWith("/flags/")) {
                            fixture("flags-v2.json")
                        } else {
                            "{}"
                        },
                    )
            }
        mock.start(19293)
        val storage = Files.createTempDirectory("compliance-test").toFile()
        val server = embeddedServer(CIO, port = 18293) { complianceRoutes(profile, 18293, storage, closeTimeoutMs) }.start()
        try {
            val config = JsonParser.parseString("""{"api_key":"phc_test","host":"http://127.0.0.1:19293","flush_at":100}""").asJsonObject
            distinctId?.let { config.addProperty("distinct_id", it) }
            action("init", config.toString())
            test(mock)
        } finally {
            action("reset")
            server.stop(0, 1000)
            mock.shutdown()
            storage.deleteRecursively()
            http.connectionPool.evictAll()
        }
    }

    @Test(timeout = 20_000)
    fun retiredSessionCleansUpAfterTimedOutInit() {
        lateinit var ingress: String
        lateinit var sessionStorage: File
        val created = AtomicInteger()
        val closed = AtomicInteger()
        val sdkActions = AtomicInteger()
        val profile =
            object : SdkProfile by CoreProfile {
                override fun create(
                    request: InitRequest,
                    storage: File,
                    observer: Observation,
                ): SdkClient {
                    ingress = request.host
                    sessionStorage = storage
                    created.incrementAndGet()
                    val sdk = CoreProfile.create(request, storage, observer)
                    return object : SdkClient by sdk {
                        override fun capture(request: CaptureRequest) {
                            sdkActions.incrementAndGet()
                            sdk.capture(request)
                        }

                        override fun flag(request: FlagRequest): Any? {
                            sdkActions.incrementAndGet()
                            return sdk.flag(request)
                        }

                        override fun reloadFlags() {
                            sdkActions.incrementAndGet()
                            sdk.reloadFlags()
                        }

                        override fun cachedFlag(key: String): Any? {
                            sdkActions.incrementAndGet()
                            return sdk.cachedFlag(key)
                        }

                        override fun flush() {
                            sdkActions.incrementAndGet()
                            sdk.flush()
                        }

                        override fun close() {
                            sdk.close()
                            closed.incrementAndGet()
                        }
                    }
                }
            }

        fun assertRetiredActionsRejected() {
            for ((name, body) in listOf(
                "capture" to """{"distinct_id":"user","event":"retired"}""",
                "get_feature_flag" to """{"distinct_id":"user","key":"test-flag"}""",
                "reload_feature_flags" to "{}",
                "get_cached_feature_flag" to """{"key":"test-flag"}""",
                "flush" to "{}",
            )) {
                val error = assertFailsWith<IllegalStateException> { action(name, body) }
                assertTrue(error.message.orEmpty().contains("Session is retired"))
            }
            assertEquals(0, sdkActions.get(), "Retired actions must reject before invoking the SDK")
        }

        withAdapter(profile, closeTimeoutMs = 100) { mock ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            mock.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.path != "/probe") return MockResponse().setBody("{}")
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "Held ingress was not released" }
                        return MockResponse().setBody("original response")
                    }
                }
            val oldIngress = ingress
            val oldStorage = sessionStorage
            val pending =
                executor.submit {
                    http.newCall(Request.Builder().url("$oldIngress/probe").build()).execute().use {
                        assertEquals(200, it.code)
                        assertEquals("original response", it.body!!.string())
                    }
                }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                for (actionName in listOf("init", "reset")) {
                    val error =
                        assertFailsWith<IllegalStateException> {
                            action(actionName, """{"api_key":"phc_test","host":"http://127.0.0.1:19293"}""")
                        }
                    assertTrue(error.message.orEmpty().contains("Previous session cleanup is still pending"))
                }
                assertEquals(1, created.get())
                assertEquals(0, closed.get())
                assertTrue(oldStorage.exists())
                assertRetiredActionsRejected()
                http.newCall(Request.Builder().url("$oldIngress/probe").build()).execute().use {
                    assertEquals(410, it.code)
                }

                release.countDown()
                pending.get(5, TimeUnit.SECONDS)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (oldStorage.exists() && System.nanoTime() < deadline) Thread.sleep(10)
                assertFalse(oldStorage.exists(), "Retired storage must be removed without another reset/init")
                assertEquals(1, closed.get())
                assertRetiredActionsRejected()

                action("init", """{"api_key":"phc_test","host":"http://127.0.0.1:19293"}""")
                assertEquals(2, created.get())
                assertEquals(1, closed.get())
                assertTrue(sessionStorage.exists())
                assertTrue(ingress != oldIngress)
                action("reset")
                assertEquals(2, closed.get())
                assertFalse(sessionStorage.exists())
            } finally {
                release.countDown()
                executor.shutdown()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
        assertEquals(2, closed.get())
    }

    @Test(timeout = 20_000)
    fun coreBootstrapsIdentityBeforeCaptureAndExplicitFlagLoad() =
        withAdapter(CoreProfile, distinctId = "client-user-café 雪") { mock ->
            assertEquals(0, mock.requestCount)
            val captured = action("capture", """{"event":"before-flags"}""")
            assertTrue(action("flush")["success"].asBoolean)
            val batch = mock.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/batch", batch.path)
            val event = batchEvents(batch).single().asJsonObject
            assertEquals("client-user-café 雪", event["distinct_id"].asString)
            assertEquals(captured["uuid"].asString, event["uuid"].asString)

            assertTrue(action("reload_feature_flags")["success"].asBoolean)
            val flags = mock.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/flags/?v=2", flags.path)
            assertEquals("client-user-café 雪", JsonParser.parseString(flags.body.readUtf8()).asJsonObject["distinct_id"].asString)
            for (i in 1..2) {
                assertEquals("variant-a", action("get_cached_feature_flag", """{"key":"test-flag"}""")["value"].asString)
            }
            assertTrue(action("flush")["success"].asBoolean)
            val calledBatch = mock.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/batch", calledBatch.path)
            val called = batchEvents(calledBatch).single().asJsonObject
            assertEquals("\$feature_flag_called", called["event"].asString)
            assertEquals("client-user-café 雪", called["distinct_id"].asString)
            assertEquals(3, mock.requestCount)

            mock.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        MockResponse().setHeader("Content-Type", "application/json").setBody(
                            if (request.path!!.startsWith("/flags/")) fixture("flags-v2.json").replace("variant-a", "variant-b") else "{}",
                        )
                }
            assertEquals("variant-a", action("get_cached_feature_flag", """{"key":"test-flag"}""")["value"].asString)
            assertTrue(action("reload_feature_flags")["success"].asBoolean)
            assertEquals("variant-b", action("get_cached_feature_flag", """{"key":"test-flag"}""")["value"].asString)
            assertTrue(action("flush")["success"].asBoolean)
            val later = generateSequence { mock.takeRequest(200, TimeUnit.MILLISECONDS) }.toList()
            assertEquals(1, later.count { it.path!!.startsWith("/flags/") })
            val laterEvents = later.filter { it.path == "/batch" }.flatMap { batchEvents(it) }
            assertTrue(laterEvents.all { it.asJsonObject["event"].asString == "\$feature_flag_called" })
            val requestsBefore = mock.requestCount
            val error =
                assertFailsWith<IllegalStateException> {
                    action("get_feature_flag", """{"key":"test-flag","distinct_id":"different-user"}""")
                }
            assertTrue(error.message.orEmpty().contains("requires reset/init"))
            assertEquals(requestsBefore, mock.requestCount)
        }

    private fun batchEvents(request: RecordedRequest): List<com.google.gson.JsonElement> {
        val body = GZIPInputStream(request.body.inputStream()).reader().readText()
        return JsonParser.parseString(body).asJsonObject["batch"].asJsonArray.toList()
    }

    @Test(timeout = 40_000)
    fun coreExplicitReloadRetainsNativeRetries() {
        for (status in listOf(502, 504)) {
            withAdapter(CoreProfile, distinctId = "retry-user") { mock ->
                val attempts = AtomicInteger()
                mock.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            if (request.path!!.startsWith("/flags/")) {
                                MockResponse().setResponseCode(if (attempts.incrementAndGet() == 1) status else 200)
                                    .setHeader("Content-Type", "application/json").setBody(fixture("flags-v2.json"))
                            } else {
                                MockResponse().setBody("{}")
                            }
                    }
                assertTrue(action("reload_feature_flags")["success"].asBoolean)
                assertEquals("variant-a", action("get_cached_feature_flag", """{"key":"test-flag"}""")["value"].asString)
                assertTrue(action("flush")["success"].asBoolean)
                val requests = generateSequence { mock.takeRequest(200, TimeUnit.MILLISECONDS) }.toList()
                assertEquals(2, attempts.get())
                assertEquals(2, requests.count { it.path!!.startsWith("/flags/") })
                val events = requests.filter { it.path == "/batch" }.flatMap { batchEvents(it) }
                assertEquals(listOf("\$feature_flag_called"), events.map { it.asJsonObject["event"].asString })
            }
        }
    }

    @Test(timeout = 20_000)
    fun serverEvaluationsKeepPerCallIdentity() =
        withAdapter(ServerProfile) { mock ->
            for (user in listOf("first-user", "second-user")) {
                val result = action("get_feature_flag", """{"key":"test-flag","distinct_id":"$user","force_remote":true}""")
                assertEquals("variant-a", result["value"].asString)
                val flags = mock.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("/flags/?v=2", flags.path)
                assertEquals(user, JsonParser.parseString(flags.body.readUtf8()).asJsonObject["distinct_id"].asString)
                assertTrue(action("flush")["success"].asBoolean)
                val events = generateSequence { mock.takeRequest(200, TimeUnit.MILLISECONDS) }.flatMap { batchEvents(it) }.toList()
                assertEquals(listOf("\$feature_flag_called"), events.map { it.asJsonObject["event"].asString })
            }
            for (name in listOf("reload_feature_flags", "get_cached_feature_flag")) {
                assertFailsWith<IllegalStateException> { action(name, """{"key":"test-flag"}""") }
            }
            val error =
                assertFailsWith<IllegalStateException> {
                    action("init", """{"api_key":"phc_test","host":"http://127.0.0.1:19293","distinct_id":"user"}""")
                }
            assertTrue(error.message.orEmpty().contains("bootstrap_identity"))
        }

    @Test(timeout = 10_000)
    fun blankBootstrapIdentityRejectsBeforeReplacingClient() =
        withAdapter(CoreProfile, distinctId = "original-user") { mock ->
            for (id in listOf("", " ")) {
                val error =
                    assertFailsWith<IllegalStateException> {
                        action("init", """{"api_key":"phc_test","host":"http://127.0.0.1:19293","distinct_id":"$id"}""")
                    }
                assertTrue(error.message.orEmpty().contains("must not be blank"))
            }
            action("reload_feature_flags")
            val flags = mock.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("original-user", JsonParser.parseString(flags.body.readUtf8()).asJsonObject["distinct_id"].asString)
        }

    private fun captureTimestamp(profile: SdkProfile) =
        withAdapter(profile) { mock ->
            val captured = action("capture", fixture("capture-offset.json"))
            val flushed = action("flush")
            assertTrue(flushed["success"].asBoolean)
            assertEquals(1, flushed["events_flushed"].asInt)
            val request = mock.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/batch", request.path)
            assertEquals("gzip", request.getHeader("Content-Encoding"))
            val body = GZIPInputStream(request.body.inputStream()).reader().readText()
            val event = JsonParser.parseString(body).asJsonObject["batch"].asJsonArray.single().asJsonObject
            assertEquals(captured["uuid"].asString, event["uuid"].asString)
            assertEquals("2025-01-02T03:04:05.000Z", event["timestamp"].asString)
            assertEquals("2025-01-02T08:34:05+05:30", event["properties"].asJsonObject["timestamp_like"].asString)
            assertEquals(0, action("flush")["events_flushed"].asInt)
        }

    @Test fun coreTimestampAndReturnedUuidComeFromQueuedEvent() = captureTimestamp(CoreProfile)

    @Test fun serverTimestampAndReturnedUuidComeFromQueuedEvent() = captureTimestamp(ServerProfile)

    private fun flags(profile: SdkProfile) =
        withAdapter(profile) { mock ->
            val result = action("get_feature_flag", """{"key":"test-flag","distinct_id":"user","force_remote":true}""")
            assertEquals("variant-a", result["value"].asString)
            val flags = mock.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/flags/?v=2", flags.path)
            assertEquals(null, flags.getHeader("Authorization"))
            action("flush")
            val batches = generateSequence { mock.takeRequest(300, TimeUnit.MILLISECONDS) }.filter { it.path == "/batch" }.toList()
            val events =
                batches.flatMap {
                    val body = GZIPInputStream(it.body.inputStream()).reader().readText()
                    JsonParser.parseString(body).asJsonObject["batch"].asJsonArray.toList()
                }
            assertEquals(1, events.count { it.asJsonObject["event"].asString == "\$feature_flag_called" })
            assertFalse(events.isEmpty())
        }

    @Test fun coreReloadUsesSdkResultAndCalledEvent() = flags(CoreProfile)

    @Test fun serverSnapshotUsesSdkResultAndCalledEvent() = flags(ServerProfile)

    @Test
    fun capturePreservesLargeIntegerPropertiesOnTheWire() =
        withAdapter(CoreProfile) { mock ->
            action("capture", fixture("capture-large-integers.json"))
            action("flush")
            val request = mock.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/batch", request.path)
            val body = GZIPInputStream(request.body.inputStream()).reader().readText()
            val event = JsonParser.parseString(body).asJsonObject["batch"].asJsonArray.single().asJsonObject
            assertEquals("large-integer-properties", event["event"].asString)
            val properties = event["properties"].asJsonObject
            assertEquals(9007199254740993L, properties["exact"].asLong)
            assertEquals(9007199254740993L, properties["nested"].asJsonObject["exact"].asLong)
        }

    private fun ingressPreservesResponse(
        method: String,
        status: Int = 503,
    ) {
        lateinit var ingress: String
        val profile =
            object : SdkProfile by CoreProfile {
                override fun create(
                    request: InitRequest,
                    storage: File,
                    observer: Observation,
                ): SdkClient {
                    ingress = request.host
                    return CoreProfile.create(request, storage, observer)
                }
            }
        withAdapter(profile) { mock ->
            mock.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        MockResponse().setResponseCode(status).setHeader("X-Upstream", "unchanged").apply {
                            if (status == 503) {
                                setHeader("Retry-After", "0").setBody("upstream unavailable")
                            } else {
                                setHeader("Content-Encoding", "gzip").removeHeader("Content-Length")
                                setHeader("Connection", "close")
                            }
                        }
                }
            val before = mock.requestCount
            val connection = URL("$ingress/probe").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.instanceFollowRedirects = false
                connection.readTimeout = 5000
                if (method == "POST") {
                    connection.doOutput = true
                    connection.outputStream.use { it.write("original request".toByteArray()) }
                }
                assertEquals(status, connection.responseCode)
                assertEquals("unchanged", connection.getHeaderField("X-Upstream"))
                if (status == 503) {
                    assertEquals("0", connection.getHeaderField("Retry-After"))
                    assertEquals("upstream unavailable", connection.errorStream.reader().readText())
                } else {
                    assertEquals("gzip", connection.getHeaderField("Content-Encoding"))
                    assertEquals("", connection.inputStream.reader().readText())
                }
                assertEquals(before + 1, mock.requestCount)
                val request = mock.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals(method, request.method)
                assertEquals(if (method == "POST") "original request" else "", request.body.readUtf8())
            } finally {
                connection.disconnect()
            }
        }
    }

    @Test fun ingressDoesNotRetry503Post() = ingressPreservesResponse("POST")

    @Test fun ingressDoesNotRetry503BodylessGet() = ingressPreservesResponse("GET")

    @Test fun ingressPreservesBodyless304WithGzipMetadata() = ingressPreservesResponse("GET", 304)

    @Test fun ingressPreservesBodyless204WithGzipMetadata() = ingressPreservesResponse("GET", 204)

    @Test(timeout = 10_000)
    fun coreRejectsChangingAnIdentifiedFlagUserWithoutReloading() =
        withAdapter(CoreProfile) { mock ->
            action("get_feature_flag", """{"key":"test-flag","distinct_id":"user-a"}""")
            val requestsBefore = mock.requestCount
            val error =
                assertFailsWith<IllegalStateException> {
                    action("get_feature_flag", """{"key":"test-flag","distinct_id":"user-b"}""")
                }
            assertTrue(error.message.orEmpty().contains("requires reset/init"))
            assertEquals(requestsBefore, mock.requestCount)
            val result = action("get_feature_flag", """{"key":"test-flag","distinct_id":"user-a"}""")
            assertEquals("variant-a", result["value"].asString)
        }
}
