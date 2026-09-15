package com.posthog.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.posthog.internal.PostHogApi
import com.posthog.server.internal.PostHogFeatureFlags
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class PostHogNullPropertyTest {
    @Test
    fun `server capture exception and hook properties preserve null slots in memory queue batch`() {
        withServer { sut, http ->
            val properties = mapOf("nested" to mapOf("drop" to null), "items" to listOf("1", null, 2, mapOf("drop" to null), listOf(null)))
            sut.capture("user", "Nullable", properties)
            sut.captureException(IllegalStateException("synthetic"), "user", properties)
            sut.capture("user", "Dropped", properties)
            assertEquals(0, http.requestCount)
            http.enqueue(MockResponse().setBody("{}"))
            sut.flush()
            val request = assertNotNull(http.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/batch", request.path)
            val events = JsonParser.parseString(request.body.readUtf8()).asJsonObject.getAsJsonArray("batch")
            assertEquals(setOf("Nullable", "${'$'}exception"), events.map { it.asJsonObject["event"].asString }.toSet())
            events.forEach {
                val actual = it.asJsonObject.getAsJsonObject("properties")
                assertEquals(JsonParser.parseString("""["1",null,2,{},[null]]"""), actual["items"])
                assertEquals(JsonObject(), actual["nested"])
                assertHook(actual)
            }
            assertTrue((properties["nested"] as Map<*, *>).containsKey("drop"))
            assertEquals(5, (properties["items"] as List<*>).size)
        }
    }

    @Test
    fun `fatal server memory queue serializes hook properties on blocking path`() {
        withServer { sut, http ->
            http.enqueue(MockResponse().setBody("{}"))
            // Exercise the fatal queue marker without installing a handler or crashing the process.
            sut.capture("user", "${'$'}exception", mapOf("${'$'}exception_level" to "fatal"))
            assertEquals(1, http.requestCount)
            val request = assertNotNull(http.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/batch", request.path)
            val event = JsonParser.parseString(request.body.readUtf8()).asJsonObject.getAsJsonArray("batch").single().asJsonObject
            assertEquals("fatal", event.getAsJsonObject("properties")["${'$'}exception_level"].asString)
            assertHook(event.getAsJsonObject("properties"))
        }
    }

    @Test
    fun `reading flag definitions from cache retains baseline null array compaction`() {
        withFlagCache(fetch = false)
    }

    @Test
    fun `writing flag definitions to cache retains baseline null array compaction`() {
        withFlagCache(fetch = true)
    }

    private fun withFlagCache(fetch: Boolean) {
        val http = MockWebServer()
        http.start(InetAddress.getByName("127.0.0.1"), 0)
        val transport =
            OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false)
                .followSslRedirects(false)
                .addInterceptor { chain ->
                    if (!fetch || chain.request().url.host != "127.0.0.1") {
                        throw IOException("Unexpected SDK request: ${chain.request().url}")
                    }
                    chain.proceed(chain.request())
                }
                .build()
        val config =
            com.posthog.PostHogConfig(
                "null-properties-cache-test",
                http.url("/").newBuilder().host("127.0.0.1").build().toString(),
            ).apply { httpClient = transport }
        val definition =
            """
            {"flags":[{"id":1,"name":"cached","key":"cached","active":true,"version":1,
            "filters":{"groups":[{"properties":[],"rollout_percentage":100}],"payloads":{"true":[null,"x"]}}}],
            "group_type_mapping":{},"cohorts":{}}
            """.trimIndent()
        val cacheData: Map<String, Any?> = config.serializer.deserialize(definition.reader())
        var storedData: Map<String, Any?>? = null
        val provider =
            object : PostHogBlockingFlagDefinitionCacheProvider() {
                override fun getFlagDefinitionsBlocking() = cacheData

                override fun shouldFetchFlagDefinitionsBlocking() = fetch

                override fun onFlagDefinitionsReceivedBlocking(data: Map<String, Any?>) {
                    storedData = data
                }
            }
        val sut =
            PostHogFeatureFlags(
                config,
                PostHogApi(config),
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "null-properties-personal-test",
                pollerEnabled = false,
                flagDefinitionCacheProvider = provider,
            )
        try {
            if (fetch) http.enqueue(MockResponse().setBody(definition))
            sut.loadFeatureFlagDefinitions()
            if (fetch) {
                val request = assertNotNull(http.takeRequest(5, TimeUnit.SECONDS))
                assertTrue(request.path!!.startsWith("/api/feature_flag/local_evaluation/"))
                val flags = assertNotNull(storedData)["flags"] as List<*>
                val filters = (flags.single() as Map<*, *>)["filters"] as Map<*, *>
                assertEquals(listOf("x"), (filters["payloads"] as Map<*, *>)["true"])
                assertEquals(1, http.requestCount)
            } else {
                // Local evaluation retains its existing list-to-string payload conversion.
                assertEquals("[x]", sut.getFeatureFlagPayload("cached", distinctId = "user"))
                assertEquals(0, http.requestCount)
            }
            val originalFilters = ((cacheData["flags"] as List<*>).single() as Map<*, *>)["filters"] as Map<*, *>
            assertEquals(listOf(null, "x"), (originalFilters["payloads"] as Map<*, *>)["true"])
        } finally {
            sut.clear()
            transport.connectionPool.evictAll()
            transport.dispatcher.executorService.shutdown()
            http.shutdown()
        }
    }

    private fun assertHook(properties: JsonObject) {
        assertFalse(properties.has("hookNull"))
        assertEquals(JsonParser.parseString("""[null,{},[null],false,0,""]"""), properties["hookItems"])
    }

    private fun withServer(test: (PostHog, MockWebServer) -> Unit) {
        val http = MockWebServer()
        http.start(InetAddress.getByName("127.0.0.1"), 0)
        val transport =
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
        val serverConfig =
            PostHogConfig(
                "null-properties-server-test",
                http.url("/").newBuilder().host("127.0.0.1").build().toString(),
                preloadFeatureFlags = false,
                flushAt = 100,
            ).apply {
                flushIntervalSeconds = 3600
                addBeforeSend { event ->
                    if (event.event == "Dropped") {
                        null
                    } else {
                        // Java-style null entries remain legal in the existing nullable runtime API.
                        @Suppress("UNCHECKED_CAST")
                        val properties = event.properties as MutableMap<String, Any?>
                        properties["hookNull"] = null
                        properties["hookItems"] = listOf(null, mapOf("drop" to null), listOf(null), false, 0, "")
                        event
                    }
                }
            }
        // Use the real server config conversion (including its memory queue), with guarded HTTP.
        val config = serverConfig.asCoreConfig().apply { httpClient = transport }
        val sut = PostHog()
        try {
            sut.setup(config)
            test(sut, http)
        } finally {
            sut.close()
            transport.connectionPool.evictAll()
            transport.dispatcher.executorService.shutdown()
            http.shutdown()
        }
    }
}
