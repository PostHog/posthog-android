package com.posthog.server.internal

import com.posthog.PostHogConfig
import com.posthog.internal.PostHogApi
import com.posthog.internal.PostHogLogger
import com.posthog.server.CountingDispatcher
import com.posthog.server.PostHogBlockingFlagDefinitionCacheProvider
import com.posthog.server.PostHogFlagDefinitionCacheProvider
import com.posthog.server.createFlagsResponse
import com.posthog.server.createTestConfig
import com.posthog.server.jsonResponse
import com.posthog.server.shutdownAndAwaitTermination
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class VersionedPropertyMatchingTest {
    private val keys = listOf("person", "group", "cohort", "dependency")
    private val properties = mapOf("value" to "banana")
    private val groups = mapOf("company" to "company-1")
    private val groupProperties = mapOf("company" to properties)

    private fun definitions(version: Int?): String {
        val leaf = """{"key": "value", "value": false, "operator": "exact", "type": "person"}"""
        val cohort = """{"key": "id", "value": "outer", "type": "cohort"}"""
        val dependency =
            """
            {"key": "cohort", "value": true, "operator": "flag_evaluates_to",
             "type": "flag", "dependency_chain": ["cohort"]}
            """.trimIndent()

        fun flag(
            key: String,
            condition: String,
            group: Boolean = false,
        ): String =
            """
            {"id": 1, "name": "$key", "key": "$key", "active": true, "version": 42,
             "filters": {${if (group) "\"aggregation_group_type_index\": 0," else ""}
               "groups": [{"properties": [$condition]}]}}
            """.trimIndent()

        return """
            {
              ${version?.let { "\"property_matching_version\": $it," } ?: ""}
              "flags": [${flag("person", leaf)}, ${flag("group", leaf, true)},
                        ${flag("cohort", cohort)}, ${flag("dependency", dependency)}],
              "group_type_mapping": {"0": "company"},
              "cohorts": {
                "outer": {"type": "AND", "values": [{"type": "OR", "values": [{"key": "id", "value": "inner", "type": "cohort"}]}]},
                "inner": {"type": "AND", "values": [$leaf]}
              }
            }
            """.trimIndent()
    }

    private fun createSut(
        config: PostHogConfig,
        provider: PostHogFlagDefinitionCacheProvider? = null,
    ): PostHogFeatureFlags =
        PostHogFeatureFlags(
            config,
            PostHogApi(config),
            60000,
            100,
            localEvaluation = true,
            personalApiKey = "personal",
            pollerEnabled = false,
            flagDefinitionCacheProvider = provider,
        )

    private fun assertLocalResults(
        sut: PostHogFeatureFlags,
        expected: Boolean,
    ) {
        for (key in keys) {
            assertEquals(
                expected,
                sut.getFeatureFlag(
                    key,
                    distinctId = "user",
                    personProperties = properties,
                    groups = groups,
                    groupProperties = groupProperties,
                ),
                key,
            )
            assertEquals(expected, sut.getFeatureFlagResult(key, "user", groups, properties, groupProperties)?.enabled, key)
        }
        val all = sut.getFeatureFlags("user", groups, properties, groupProperties)
        assertEquals(keys.toSet(), all?.keys)
        assertTrue(all!!.values.all { it.enabled == expected })
        // Selecting only the dependent flag must still use the full snapshot for recursive evaluation.
        for (selection in listOf(null, listOf("dependency"))) {
            val result = sut.evaluateFlags("user", groups, properties, groupProperties, selection, true, false)
            assertEquals(selection?.toSet() ?: keys.toSet(), result.flags.keys)
            assertTrue(result.flags.values.all { it.enabled == expected })
            assertTrue(result.locallyEvaluated.values.all { it })
        }
    }

    @Test
    fun `definitions response selects explicit boolean matching`() {
        val http = MockWebServer()
        http.enqueue(jsonResponse(definitions(2)))
        http.start()
        val sut = createSut(createTestConfig(host = http.url("/").toString()))
        try {
            sut.loadFeatureFlagDefinitions()
            assertLocalResults(sut, false)
            assertEquals(1, http.requestCount)
        } finally {
            sut.clear()
            sut.shutDown()
            http.shutdown()
        }
    }

    @Test
    fun `is_not complements person group cohort and dependency leaf results`() {
        val http = MockWebServer()
        http.start()
        val sut = createSut(createTestConfig(host = http.url("/").toString()))
        try {
            for (version in listOf(null, 1, 2)) {
                http.enqueue(jsonResponse(definitions(version).replace("\"exact\"", "\"is_not\"")))
                sut.loadFeatureFlagDefinitions()
                assertLocalResults(sut, version == 2)
            }
            assertEquals(3, http.requestCount)
        } finally {
            sut.clear()
            sut.shutDown()
            http.shutdown()
        }
    }

    @Test
    fun `version only refresh resets matching and preserves snapshot on 304 and failure`() {
        val http = MockWebServer()
        http.start()
        val sut = createSut(createTestConfig(host = http.url("/").toString()))
        try {
            for (version in listOf(null, 1, 2, 1, 2, null, 3, 2)) {
                http.enqueue(jsonResponse(definitions(version)).setHeader("ETag", "version-$version"))
                sut.loadFeatureFlagDefinitions()
                assertLocalResults(sut, version != 2)
            }
            http.enqueue(MockResponse().setResponseCode(304))
            sut.loadFeatureFlagDefinitions()
            assertLocalResults(sut, false)
            http.enqueue(MockResponse().setResponseCode(500))
            sut.loadFeatureFlagDefinitions()
            assertLocalResults(sut, false)
            assertEquals(10, http.requestCount, "All requests must be definition loads, never remote evaluation")
            repeat(10) {
                assertTrue(http.takeRequest().path!!.startsWith("/api/feature_flag/local_evaluation/"))
            }
        } finally {
            sut.clear()
            sut.shutDown()
            http.shutdown()
        }
    }

    @Test
    fun `definition refresh invalidates cached remote results even when only version changes`() {
        val http = MockWebServer()
        http.enqueue(jsonResponse(definitions(1)))
        http.enqueue(jsonResponse(createFlagsResponse("person", enabled = true)))
        http.start()
        val sut = createSut(createTestConfig(host = http.url("/").toString()))
        try {
            sut.loadFeatureFlagDefinitions()
            // Requesting an unknown flag populates the remote cache for this identity and property set.
            sut.getFeatureFlag("unknown", distinctId = "user", personProperties = properties)
            assertEquals(true, sut.getFeatureFlag("person", distinctId = "user", personProperties = properties))
            http.enqueue(jsonResponse(definitions(2)))
            sut.loadFeatureFlagDefinitions()
            assertEquals(false, sut.getFeatureFlag("person", distinctId = "user", personProperties = properties))
            http.enqueue(jsonResponse(definitions(1)))
            sut.loadFeatureFlagDefinitions()
            assertEquals(true, sut.getFeatureFlag("person", distinctId = "user", personProperties = properties))
            assertEquals(4, http.requestCount)
        } finally {
            sut.clear()
            sut.shutDown()
            http.shutdown()
        }
    }

    @Test
    fun `version only refresh prevents an in flight remote response from repopulating the cache`() {
        val responseStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val version = AtomicInteger(1)
        val dispatcher =
            CountingDispatcher(
                { jsonResponse(definitions(version.get())) },
                {
                    responseStarted.countDown()
                    check(releaseResponse.await(5, TimeUnit.SECONDS))
                    jsonResponse(createFlagsResponse("person", enabled = true))
                },
            )
        val http = MockWebServer()
        http.dispatcher = dispatcher
        http.start()
        val sut = createSut(createTestConfig(host = http.url("/").toString()))
        val executor = Executors.newSingleThreadExecutor()
        try {
            sut.loadFeatureFlagDefinitions()
            val pending =
                executor.submit<Any?> {
                    // An unknown-key fallback shares the same result-cache key as subsequent local reads.
                    sut.getFeatureFlag(
                        "unknown",
                        distinctId = "user",
                        groups = groups,
                        personProperties = properties,
                        groupProperties = groupProperties,
                    )
                }
            assertTrue(responseStarted.await(5, TimeUnit.SECONDS))
            version.set(2)
            sut.loadFeatureFlagDefinitions()
            releaseResponse.countDown()
            pending.get(5, TimeUnit.SECONDS)

            assertLocalResults(sut, false)
            val result = sut.evaluateFlags("user", groups, properties, groupProperties, null, false, false)
            assertEquals(keys.toSet(), result.flags.keys)
            assertTrue(result.flags.values.all { !it.enabled })
            assertTrue(result.locallyEvaluated.values.all { it })
            assertEquals(2, dispatcher.localEvaluationCalls.get())
            assertEquals(1, dispatcher.flagsCalls.get(), "New-snapshot reads must not fall back remotely")
        } finally {
            releaseResponse.countDown()
            executor.shutdownAndAwaitTermination()
            sut.clear()
            sut.shutDown()
            http.shutdown()
        }
    }

    @Test
    fun `disk cache round trip retains version and older entries reset to legacy`() {
        val http = MockWebServer()
        http.enqueue(jsonResponse(definitions(2)))
        http.start()
        val config = createTestConfig(host = http.url("/").toString())
        val file = Files.createTempFile("posthog-definitions", ".json").toFile()
        var fetch = true
        var cacheUnavailable = false
        val provider =
            object : PostHogBlockingFlagDefinitionCacheProvider() {
                override fun shouldFetchFlagDefinitionsBlocking(): Boolean = fetch

                override fun getFlagDefinitionsBlocking(): Map<String, Any?>? =
                    if (cacheUnavailable) null else file.reader().use { config.serializer.deserialize(it) }

                override fun onFlagDefinitionsReceivedBlocking(data: Map<String, Any?>) {
                    file.writer().use { config.serializer.serialize(data, it) }
                }
            }
        val writer = createSut(config, provider)
        val asyncProvider =
            object : PostHogFlagDefinitionCacheProvider by provider {
                override fun getFlagDefinitions(): CompletionStage<Map<String, Any?>?> =
                    CompletableFuture.supplyAsync { provider.getFlagDefinitionsBlocking() }
            }
        val reader = createSut(config, asyncProvider)
        try {
            writer.loadFeatureFlagDefinitions()
            assertEquals(2, (provider.getFlagDefinitionsBlocking()!!["property_matching_version"] as Number).toInt())
            fetch = false
            reader.loadFeatureFlagDefinitions()
            assertLocalResults(reader, false)
            cacheUnavailable = true
            reader.loadFeatureFlagDefinitions()
            assertLocalResults(reader, false)
            cacheUnavailable = false
            file.writeText("invalid-json")
            reader.loadFeatureFlagDefinitions()
            assertLocalResults(reader, false)
            for (version in listOf(1, 2, null)) {
                file.writeText(definitions(version))
                reader.loadFeatureFlagDefinitions()
                assertLocalResults(reader, version != 2)
            }
            assertEquals(1, http.requestCount, "Cache hydration must not fetch or fall back remotely")
        } finally {
            writer.clear()
            writer.shutDown()
            reader.clear()
            reader.shutDown()
            http.shutdown()
            file.delete()
        }
    }

    @Test
    fun `evaluation retains one snapshot when definitions refresh during a pass`() {
        val http = MockWebServer()
        http.enqueue(jsonResponse(definitions(1)))
        http.enqueue(jsonResponse(definitions(2)))
        http.start()
        val config = createTestConfig(host = http.url("/").toString())
        val sut = createSut(config)
        try {
            sut.loadFeatureFlagDefinitions()
            var refresh = true
            config.logger =
                object : PostHogLogger {
                    override fun isEnabled(): Boolean = true

                    override fun log(message: String) {
                        if (refresh && message.startsWith("Attempting local evaluation for distinctId:")) {
                            refresh = false
                            sut.loadFeatureFlagDefinitions()
                        }
                    }
                }
            val result = sut.evaluateFlags("user", groups, properties, groupProperties, null, true, false)
            assertEquals(keys.toSet(), result.flags.keys)
            assertTrue(result.flags.values.all { it.enabled }, "The in-progress pass must retain legacy definitions and matcher")
            assertLocalResults(sut, false)
            assertEquals(2, http.requestCount)
        } finally {
            sut.clear()
            sut.shutDown()
            http.shutdown()
        }
    }
}
