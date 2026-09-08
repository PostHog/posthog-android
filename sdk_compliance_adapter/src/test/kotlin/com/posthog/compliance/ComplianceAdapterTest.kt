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
import java.util.concurrent.TimeUnit
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
        val server = embeddedServer(CIO, port = 18293) { complianceRoutes(profile, 18293, storage) }.start()
        try {
            action("init", """{"api_key":"phc_test","host":"http://127.0.0.1:19293","flush_at":100}""")
            test(mock)
        } finally {
            action("reset")
            server.stop(0, 1000)
            mock.shutdown()
            storage.deleteRecursively()
            http.connectionPool.evictAll()
        }
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
