package com.posthog.internal

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.PostHogStateless
import com.posthog.logs.PostHogLogRecord
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class PostHogQueueNullPropertyTest {
    @get:Rule
    val tmpDir = TemporaryFolder(File("build").apply { mkdirs() })

    @Test
    fun `hook properties are normalized on disk and fresh queue restore to wire`() {
        val http = MockWebServer()
        http.start(InetAddress.getByName("127.0.0.1"), 0)
        val executor = Executors.newSingleThreadScheduledExecutor()
        val flagsExecutor = Executors.newSingleThreadScheduledExecutor()
        val transport = loopbackClient()
        val config =
            PostHogConfig("null-properties-test", http.url("/").newBuilder().host("127.0.0.1").build().toString()).apply {
                storagePrefix = tmpDir.root.absolutePath
                flushAt = 100
                flushIntervalSeconds = 3600
                preloadFeatureFlags = false
                httpClient = transport
                addBeforeSend { event ->
                    if (event.event == "Dropped") {
                        null
                    } else {
                        event.properties!!["hook"] = mapOf("drop" to null, "items" to listOf(null, mapOf("drop" to null)))
                        event
                    }
                }
            }
        val sut =
            object : PostHogStateless(executor, flagsExecutor) {
                fun clear() = queue?.clear()
            }
        var restored: PostHogQueue<PostHogEvent>? = null
        try {
            sut.setup(config)
            val input = NullPropertyInputs.properties()
            sut.captureStateless("Nullable", "user", input)
            sut.captureStateless("OnlyNull", "user", input.filterKeys { it == "test" })
            sut.captureExceptionStateless(IllegalStateException("synthetic"), "user", input)
            sut.captureStateless("Dropped", "user", input)
            executor.submit {}.get(5, TimeUnit.SECONDS)

            val files = File(tmpDir.root, config.apiKey).listFiles()!!.filter { it.extension == "event" }
            assertEquals(3, files.size)
            assertEquals(0, http.requestCount)
            val diskEvents = files.map { JsonParser.parseString(it.readText()).asJsonObject }
            diskEvents.forEach { assertProperties(it) }
            assertTrue(input.containsKey("test"))
            assertEquals(5, (input["items"] as List<*>).size)
            assertTrue((input["nested"] as Map<*, *>).containsKey("drop"))

            // A new queue must discover persisted records, not reuse the capture queue's deque.
            restored = PostHogQueue(config, EndpointSpec.batch(config, PostHogApi(config), config.storagePrefix), executor)
            http.enqueue(MockResponse().setBody("{}"))
            restored.flush()
            executor.submit {}.get(5, TimeUnit.SECONDS)
            val request = assertNotNull(http.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/batch", request.path)
            val wireEvents = JsonParser.parseString(request.body.readUtf8()).asJsonObject.getAsJsonArray("batch")
            assertEquals(diskEvents.toSet(), wireEvents.map { it.asJsonObject }.toSet())
            assertEquals(0, File(tmpDir.root, config.apiKey).listFiles()!!.size)
        } finally {
            restored?.clear()
            restored?.stop()
            sut.clear()
            executor.submit {}.get(5, TimeUnit.SECONDS)
            sut.close()
            executor.shutdown()
            flagsExecutor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue(flagsExecutor.awaitTermination(5, TimeUnit.SECONDS))
            transport.connectionPool.evictAll()
            transport.dispatcher.executorService.shutdown()
            http.shutdown()
        }
    }

    @Test
    fun `previously persisted null members are omitted on restored batch wire`() {
        val http = MockWebServer()
        http.start(InetAddress.getByName("127.0.0.1"), 0)
        val executor = Executors.newSingleThreadScheduledExecutor()
        val transport = loopbackClient()
        val config =
            PostHogConfig("null-properties-test", http.url("/").newBuilder().host("127.0.0.1").build().toString()).apply {
                storagePrefix = tmpDir.root.absolutePath
                httpClient = transport
            }
        val sut = PostHogQueue(config, EndpointSpec.batch(config, PostHogApi(config), config.storagePrefix), executor)
        try {
            val directory = File(tmpDir.root, config.apiKey).apply { mkdirs() }
            File(directory, "restored.event").writeText(
                """
                {"event":"Restored","distinct_id":"user","properties":
                 {"drop":null,"nested":{"drop":null},"items":["1",null,2,{"drop":null},[null]]}}
                """.trimIndent(),
            )
            http.enqueue(MockResponse().setBody("{}"))
            sut.flush()
            executor.submit {}.get(5, TimeUnit.SECONDS)
            val request = assertNotNull(http.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/batch", request.path)
            val properties =
                JsonParser.parseString(request.body.readUtf8()).asJsonObject.getAsJsonArray("batch")[0]
                    .asJsonObject["properties"]
            assertEquals(JsonParser.parseString("""{"nested":{},"items":["1",null,2,{},[null]]}"""), properties)
            assertEquals(0, directory.listFiles()!!.size)
        } finally {
            sut.clear()
            sut.stop()
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            transport.connectionPool.evictAll()
            transport.dispatcher.executorService.shutdown()
            http.shutdown()
        }
    }

    @Test
    fun `log disk codec retains baseline null array compaction`() {
        val transport = OkHttpClient.Builder().addInterceptor { throw IOException("SDK requests forbidden") }.build()
        val config = PostHogConfig("null-properties-test", "http://127.0.0.1").apply { httpClient = transport }
        val codec = EndpointSpec.logs(config, PostHogApi(config), tmpDir.root.absolutePath)
        val record = PostHogLogRecord("example", attributes = mapOf("items" to listOf(null, "x")))
        try {
            val file = tmpDir.newFile("record.log")
            file.outputStream().use { codec.encode(record, it) }
            val attributes = JsonParser.parseString(file.readText()).asJsonObject["attributes"]
            assertEquals(JsonParser.parseString("""{"items":["x"]}"""), attributes)
            val restored = file.inputStream().use { codec.decode(it) }
            assertEquals(listOf("x"), assertNotNull(restored).attributes["items"])
            assertEquals(listOf(null, "x"), record.attributes["items"])
        } finally {
            transport.connectionPool.evictAll()
            transport.dispatcher.executorService.shutdown()
        }
    }

    private fun assertProperties(event: JsonObject) {
        val properties = event.getAsJsonObject("properties")
        assertFalse(properties.has("test"))
        assertEquals(JsonParser.parseString("""{"items":[null,{}]}"""), properties["hook"])
        if (event["event"].asString != "OnlyNull") {
            assertEquals(JsonParser.parseString("""["1",null,2,{},[null]]"""), properties["items"])
            assertEquals(JsonParser.parseString("""[null,{}]"""), properties["array"])
            assertEquals(JsonObject(), properties["nested"])
        }
    }

    private fun loopbackClient(): OkHttpClient =
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain ->
                if (chain.request().url.host != "127.0.0.1") {
                    throw IOException("Non-loopback SDK request forbidden: ${chain.request().url}")
                }
                chain.proceed(chain.request())
            }
            .build()
}
