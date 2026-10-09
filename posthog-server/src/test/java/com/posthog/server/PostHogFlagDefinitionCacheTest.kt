package com.posthog.server

import com.posthog.PostHogOnFeatureFlags
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.StringReader
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Suppress("DEPRECATION")
internal class PostHogFlagDefinitionCacheTest {
    private fun definitions(rolloutPercentage: Int = 100): Map<String, Any?> =
        com.posthog.PostHogConfig(TEST_API_KEY).serializer.deserialize(
            StringReader(createLocalEvaluationResponse("cached-flag", rolloutPercentage = rolloutPercentage)),
        )

    private fun withProvider(
        provider: CacheProvider,
        configure: (PostHogConfig) -> Unit = {},
        block: (PostHogInterface) -> Unit,
    ) {
        val server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(503)
            }
        server.start()
        var client: PostHogInterface? = null
        try {
            val config =
                PostHogConfig.builder(TEST_API_KEY)
                    .host(server.url("/").toString())
                    .localEvaluation(true)
                    .flagDefinitionCacheProvider(provider)
                    .sendFeatureFlagEvent(false)
                    .pollIntervalSeconds(3600)
                    .flushIntervalSeconds(3600)
                    .build()
            configure(config)
            client = PostHog.with(config)
            block(client)
        } finally {
            try {
                client?.close()
                assertEquals(0, server.requestCount, "provider-only evaluation must not make HTTP requests")
                assertEquals(1, provider.calls.count { it == "shutdown" })
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun `provider-only startup hydrates definitions for public local and legacy evaluation`() {
        val provider = CacheProvider(definitions())
        val loaded = CountDownLatch(1)
        withProvider(provider, configure = { it.onFeatureFlags = PostHogOnFeatureFlags { loaded.countDown() } }) { client ->
            assertTrue(loaded.await(5, TimeUnit.SECONDS), "startup must load cached definitions")
            val local = client.evaluateFlags("user-1", onlyEvaluateLocally = true)
            assertTrue(local.isEnabled("cached-flag"))
            assertNotNull(local.definitionsLoadedAt)
            assertTrue(client.evaluateFlags("user-2").isEnabled("cached-flag"))
            assertEquals(true, client.getFeatureFlag("user-3", "cached-flag"))
            assertTrue(assertNotNull(client.getFeatureFlagResult("user-4", "cached-flag")).enabled)
            assertEquals(listOf("shouldFetch", "get"), provider.calls.toList())
        }
    }

    @Test
    fun `provider-only first public evaluation loads cached definitions`() {
        val provider = CacheProvider(definitions())
        withProvider(provider) { client ->
            assertTrue(client.evaluateFlags("user-1", onlyEvaluateLocally = true).isEnabled("cached-flag"))
            assertTrue(provider.calls.contains("get"))
        }
    }

    @Test
    fun `provider-only public reload replaces cached definitions`() {
        val provider = CacheProvider(definitions())
        val loaded = CountDownLatch(1)
        withProvider(provider, configure = { it.onFeatureFlags = PostHogOnFeatureFlags { loaded.countDown() } }) { client ->
            assertTrue(loaded.await(5, TimeUnit.SECONDS))
            assertTrue(client.evaluateFlags("user-1").isEnabled("cached-flag"))
            provider.data = definitions(rolloutPercentage = 0)
            client.reloadFeatureFlags()
            assertFalse(client.evaluateFlags("user-1", onlyEvaluateLocally = true).isEnabled("cached-flag"))
            assertEquals(listOf("shouldFetch", "get", "shouldFetch", "get"), provider.calls.toList())
        }
    }

    @Test
    fun `provider-only refresh preserves matching version on failure and resets it on fresh omission`() {
        val cacheData: Map<String, Any?> =
            com.posthog.PostHogConfig(TEST_API_KEY).serializer.deserialize(
                StringReader(
                    """
                    {
                        "property_matching_version": 2,
                        "flags": [{
                            "id": 1, "name": "cached-flag", "key": "cached-flag", "active": true,
                            "filters": {"groups": [{"properties": [
                                {"key": "value", "value": false, "operator": "exact", "type": "person"}
                            ]}]}, "version": 1
                        }],
                        "group_type_mapping": {}, "cohorts": {}
                    }
                    """.trimIndent(),
                ),
            )
        val provider = CacheProvider(cacheData)
        val loaded = CountDownLatch(1)
        withProvider(provider, configure = { it.onFeatureFlags = PostHogOnFeatureFlags { loaded.countDown() } }) { client ->
            assertTrue(loaded.await(5, TimeUnit.SECONDS))

            fun evaluate(): Boolean {
                val snapshot =
                    client.evaluateFlags("user-1", personProperties = mapOf("value" to "banana"), onlyEvaluateLocally = true)
                assertEquals(listOf("cached-flag"), snapshot.keys)
                return snapshot.isEnabled("cached-flag")
            }
            assertFalse(evaluate())
            provider.data = null
            client.reloadFeatureFlags()
            assertFalse(evaluate())
            provider.failRead = true
            client.reloadFeatureFlags()
            assertFalse(evaluate())
            provider.failRead = false
            provider.shouldFetch = true
            client.reloadFeatureFlags()
            assertFalse(evaluate())
            provider.shouldFetch = false
            provider.data = cacheData - "property_matching_version"
            client.reloadFeatureFlags()
            assertTrue(evaluate())
        }
    }

    @Test
    fun `provider-only polling refreshes definitions and shuts down the provider`() {
        val provider = CacheProvider(definitions())
        val initialLoad = CountDownLatch(1)
        val refreshed = CountDownLatch(1)
        withProvider(provider, configure = { config ->
            config.pollIntervalSeconds = 1
            config.onFeatureFlags =
                PostHogOnFeatureFlags {
                    if (initialLoad.count > 0) {
                        provider.data = definitions(rolloutPercentage = 0)
                        initialLoad.countDown()
                    } else {
                        refreshed.countDown()
                    }
                }
        }) { client ->
            assertTrue(initialLoad.await(5, TimeUnit.SECONDS))
            assertTrue(refreshed.await(5, TimeUnit.SECONDS), "poller must refresh from the provider")
            assertFalse(client.evaluateFlags("user-1", onlyEvaluateLocally = true).isEnabled("cached-flag"))
            assertTrue(provider.calls.count { it == "get" } >= 2)
        }
        val callsAfterClose = provider.calls.toList()
        assertEquals("shutdown", callsAfterClose.last())
    }

    @Test
    fun `provider-only reload preserves valid definitions on empty or failed reads and fetch decisions`() {
        val provider = CacheProvider(definitions())
        val loaded = CountDownLatch(1)
        withProvider(provider, configure = { it.onFeatureFlags = PostHogOnFeatureFlags { loaded.countDown() } }) { client ->
            assertTrue(loaded.await(5, TimeUnit.SECONDS))
            val loadedAt = client.evaluateFlags("user-1").definitionsLoadedAt
            provider.data = null
            client.reloadFeatureFlags()
            assertTrue(client.evaluateFlags("user-1", onlyEvaluateLocally = true).isEnabled("cached-flag"))
            provider.failRead = true
            client.reloadFeatureFlags()
            assertTrue(client.evaluateFlags("user-1", onlyEvaluateLocally = true).isEnabled("cached-flag"))
            provider.failRead = false
            provider.shouldFetch = true
            val readsBeforeFetch = provider.calls.count { it == "get" }
            client.reloadFeatureFlags()
            assertEquals(readsBeforeFetch, provider.calls.count { it == "get" })
            assertTrue(client.evaluateFlags("user-1", onlyEvaluateLocally = true).isEnabled("cached-flag"))
            provider.failDecision = true
            client.reloadFeatureFlags()
            val preserved = client.evaluateFlags("user-1", onlyEvaluateLocally = true)
            assertTrue(preserved.isEnabled("cached-flag"))
            assertEquals(loadedAt, preserved.definitionsLoadedAt)
        }
    }

    @Test
    fun `provider-only empty cache cannot fall back to the definition API`() {
        val provider = CacheProvider(null)
        withProvider(provider) { client ->
            client.reloadFeatureFlags()
            assertTrue(client.evaluateFlags("user-1", onlyEvaluateLocally = true).keys.isEmpty())
            assertTrue(provider.calls.contains("get"))
        }
    }

    @Test
    fun `provider-only failed cache read cannot fall back to the definition API`() {
        val provider = CacheProvider(null).apply { failRead = true }
        withProvider(provider) { client ->
            client.reloadFeatureFlags()
            assertTrue(client.evaluateFlags("user-1", onlyEvaluateLocally = true).keys.isEmpty())
            assertTrue(provider.calls.contains("get"))
        }
    }

    @Test
    fun `provider-only positive fetch decision does not read cache or fetch without a key`() {
        val provider = CacheProvider(definitions()).apply { shouldFetch = true }
        withProvider(provider) { client ->
            client.reloadFeatureFlags()
            assertTrue(client.evaluateFlags("user-1", onlyEvaluateLocally = true).keys.isEmpty())
            assertTrue(provider.calls.contains("shouldFetch"))
            assertFalse(provider.calls.contains("get"))
        }
    }

    @Test
    fun `provider-only failed fetch decision does not read cache or fetch without a key`() {
        val provider = CacheProvider(definitions()).apply { failDecision = true }
        withProvider(provider) { client ->
            client.reloadFeatureFlags()
            assertTrue(client.evaluateFlags("user-1", onlyEvaluateLocally = true).keys.isEmpty())
            assertTrue(provider.calls.contains("shouldFetch"))
            assertFalse(provider.calls.contains("get"))
        }
    }

    @Test
    fun `local evaluation opt out does not consult the definition cache`() {
        val provider = CacheProvider(definitions())
        withProvider(provider, configure = { it.localEvaluation = false }) { client ->
            client.reloadFeatureFlags()
            assertTrue(client.evaluateFlags("user-1", onlyEvaluateLocally = true).keys.isEmpty())
            assertTrue(provider.calls.isEmpty())
        }
    }

    private class CacheProvider(
        @Volatile var data: Map<String, Any?>?,
    ) : PostHogBlockingFlagDefinitionCacheProvider() {
        val calls = CopyOnWriteArrayList<String>()

        @Volatile var shouldFetch = false

        @Volatile var failRead = false

        @Volatile var failDecision = false

        override fun shouldFetchFlagDefinitionsBlocking(): Boolean {
            calls.add("shouldFetch")
            if (failDecision) error("fetch decision failed")
            return shouldFetch
        }

        override fun getFlagDefinitionsBlocking(): Map<String, Any?>? {
            calls.add("get")
            if (failRead) error("cache read failed")
            return data
        }

        override fun onFlagDefinitionsReceivedBlocking(data: Map<String, Any?>) {
            calls.add("store")
            this.data = data
        }

        override fun shutdownBlocking() {
            calls.add("shutdown")
        }
    }
}
