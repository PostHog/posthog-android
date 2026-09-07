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
