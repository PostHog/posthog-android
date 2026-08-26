package com.posthog.server.internal

import com.posthog.internal.PostHogApi
import com.posthog.server.CountingDispatcher
import com.posthog.server.PostHogBlockingFlagDefinitionCacheProvider
import com.posthog.server.PostHogFlagDefinitionCacheProvider
import com.posthog.server.TestLogger
import com.posthog.server.conclusiveFlagDefinition
import com.posthog.server.createEmptyFlagsResponse
import com.posthog.server.createFlagsResponse
import com.posthog.server.createFlagsResponseWithErrors
import com.posthog.server.createFlagsResponseWithQuotaLimited
import com.posthog.server.createLocalEvaluationResponse
import com.posthog.server.createLocalEvaluationResponseFrom
import com.posthog.server.createMockHttp
import com.posthog.server.createMultipleFlagsResponse
import com.posthog.server.createTestConfig
import com.posthog.server.errorResponse
import com.posthog.server.jsonResponse
import com.posthog.server.jsonResponseWithEtag
import com.posthog.server.notModifiedResponse
import com.posthog.server.unGzip
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.StringReader
import java.io.StringWriter
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogFeatureFlagsTest {
    @Test
    fun `getFeatureFlag returns variant when available via API`() {
        val flagsResponse = createFlagsResponse("test-flag", enabled = true, variant = "variant-a")
        val mockServer = createMockHttp(jsonResponse(flagsResponse))
        val url = mockServer.url("/")

        val config = createTestConfig(host = url.toString())
        val api = PostHogApi(config)
        val remoteConfig = PostHogFeatureFlags(config, api, 60000, 100)

        val result =
            remoteConfig.getFeatureFlag(
                key = "test-flag",
                defaultValue = "default",
                distinctId = "test-user",
            )

        assertEquals("variant-a", result)
        mockServer.shutdown()
    }

    @Test
    fun `getFeatureFlag returns enabled when variant is null`() {
        val flagsResponse = createFlagsResponse("test-flag", enabled = true, variant = null)
        val mockServer = createMockHttp(jsonResponse(flagsResponse))
        val url = mockServer.url("/")

        val config = createTestConfig(host = url.toString())
        val api = PostHogApi(config)
        val remoteConfig = PostHogFeatureFlags(config, api, 60000, 100)

        val result =
            remoteConfig.getFeatureFlag(
                key = "test-flag",
                defaultValue = "default",
                distinctId = "test-user",
            )

        assertEquals(true, result)
        mockServer.shutdown()
    }

    @Test
    fun `getFeatureFlag returns default value when flag not found`() {
        val flagsResponse = createEmptyFlagsResponse()
        val mockServer = createMockHttp(jsonResponse(flagsResponse))
        val url = mockServer.url("/")

        val config = createTestConfig(host = url.toString())
        val api = PostHogApi(config)
        val remoteConfig = PostHogFeatureFlags(config, api, 60000, 100)

        val result =
            remoteConfig.getFeatureFlag(
                key = "missing-flag",
                defaultValue = "default",
                distinctId = "test-user",
            )

        assertEquals("default", result)
        mockServer.shutdown()
    }

    @Test
    fun `getFeatureFlag returns default value when getFeatureFlags returns null`() {
        val config = createTestConfig()
        val api = PostHogApi(config)
        val remoteConfig = PostHogFeatureFlags(config, api, 60000, 100)

        // The null distinctId will cause getFeatureFlags to return null
        val result =
            remoteConfig.getFeatureFlag(
                key = "test-flag",
                defaultValue = "default",
                distinctId = null,
            )

        assertEquals("default", result)
    }

    @Test
    fun `getFeatureFlags returns null when success response is not JSON`() {
        val mockServer = createMockHttp(MockResponse().setBody("<html>upstream error</html>"))
        val url = mockServer.url("/")

        val config = createTestConfig(host = url.toString())
        val api = PostHogApi(config)
        val remoteConfig = PostHogFeatureFlags(config, api, 60000, 100)

        val result = remoteConfig.getFeatureFlags(distinctId = "test-user")

        assertNull(result)
        mockServer.shutdown()
    }

    @Test
    fun `getFeatureFlagPayload returns payload when available`() {
        val flagsResponse = createFlagsResponse("test-flag", payload = "test-payload")
        val mockServer = createMockHttp(jsonResponse(flagsResponse))
        val url = mockServer.url("/")

        val config = createTestConfig(host = url.toString())
        val api = PostHogApi(config)
        val remoteConfig = PostHogFeatureFlags(config, api, 60000, 100)

        val result =
            remoteConfig.getFeatureFlagPayload(
                key = "test-flag",
                defaultValue = "default",
                distinctId = "test-user",
            )

        assertEquals("test-payload", result)
        mockServer.shutdown()
    }

    @Test
    fun `getFeatureFlagPayload returns default value when payload is null`() {
        val flagsResponse = createFlagsResponse("test-flag", payload = null)
        val mockServer = createMockHttp(jsonResponse(flagsResponse))
        val url = mockServer.url("/")

        val config = createTestConfig(host = url.toString())
        val api = PostHogApi(config)
        val remoteConfig = PostHogFeatureFlags(config, api, 60000, 100)

        val result =
            remoteConfig.getFeatureFlagPayload(
                key = "test-flag",
                defaultValue = "default",
                distinctId = "test-user",
            )

        assertEquals("default", result)
        mockServer.shutdown()
    }

    @Test
    fun `getFeatureFlagPayload returns default value when getFeatureFlags returns null`() {
        val config = createTestConfig()
        val api = PostHogApi(config)
        val remoteConfig = PostHogFeatureFlags(config, api, 60000, 100)

        // The null distinctId will cause getFeatureFlagPayload to return null
        val result =
            remoteConfig.getFeatureFlagPayload(
                key = "test-flag",
                defaultValue = "default",
                distinctId = null,
            )

        assertEquals("default", result)
    }

    @Test
    fun `getFeatureFlags returns null when distinctId is null`() {
        val logger = TestLogger()
        val config = createTestConfig(logger)
        val api = PostHogApi(config)
        val remoteConfig = PostHogFeatureFlags(config, api, 60000, 100)

        val result = remoteConfig.getFeatureFlags()

        assertNull(result)
        assertTrue(logger.containsLog("getFeatureFlags called but no distinctId available"))
    }

    @Test
    fun `getFeatureFlags returns cached flags on cache hit`() {
        val logger = TestLogger()
        val flagsResponse = createFlagsResponse("test-flag")
        val mockServer = createMockHttp(jsonResponse(flagsResponse))
        val url = mockServer.url("/")

        val config = createTestConfig(logger, url.toString())
        val api = PostHogApi(config)
        val remoteConfig = PostHogFeatureFlags(config, api, 60000, 100)

        // First call should fetch from API and cache
        val result1 =
            remoteConfig.getFeatureFlags(
                distinctId = "test-user",
            )

        // Second call should use cache (won't make API call)
        val result2 =
            remoteConfig.getFeatureFlags(
                distinctId = "test-user",
            )

        assertTrue(result1?.isNotEmpty() == true)
        assertEquals(result1, result2)
        assertEquals(1, mockServer.requestCount) // Only one API call should be made

        mockServer.shutdown()
    }

    @Test
    fun `getFeatureFlags handles API errors gracefully`() {
        val logger = TestLogger()
        val mockServer = createMockHttp(errorResponse(500, "Internal Server Error"))
        val url = mockServer.url("/")

        val config = createTestConfig(logger, url.toString())
        val api = PostHogApi(config)
        val remoteConfig = PostHogFeatureFlags(config, api, 60000, 100)

        val result =
            remoteConfig.getFeatureFlags(
                distinctId = "test-user",
            )

        assertNull(result)
        assertTrue(logger.containsLog("Loading remote feature flags API error"))
        mockServer.shutdown()
    }

    @Test
    fun `clear logs message and empties cache`() {
        val logger = TestLogger()
        val flagsResponse = createFlagsResponse("test-flag")

        val mockServer =
            createMockHttp(
                jsonResponse(flagsResponse),
                jsonResponse(flagsResponse),
            )
        val url = mockServer.url("/")

        val config = createTestConfig(logger, url.toString())
        val api = PostHogApi(config)
        val remoteConfig = PostHogFeatureFlags(config, api, 60000, 100)

        // Populate cache
        remoteConfig.getFeatureFlags("test-user")
        assertEquals(1, mockServer.requestCount) // One API call made

        // Clear cache
        remoteConfig.clear()
        assertTrue(logger.containsLog("Feature flags cache cleared"))

        // Next call should be cache miss again
        logger.clear()
        remoteConfig.getFeatureFlags("test-user")
        assertEquals(2, mockServer.requestCount) // Second API call made

        mockServer.shutdown()
    }

    @Test
    fun `cache differentiates between different distinctIds`() {
        val logger = TestLogger()
        val flagsResponse = createFlagsResponse("test-flag")

        val mockServer =
            createMockHttp(
                jsonResponse(flagsResponse),
                jsonResponse(flagsResponse),
            )
        val url = mockServer.url("/")

        val config = createTestConfig(logger, url.toString())
        val api = PostHogApi(config)
        val remoteConfig = PostHogFeatureFlags(config, api, 60000, 100)

        // Different distinctIds should result in different cache entries
        val result1 = remoteConfig.getFeatureFlags("user1")
        val result2 = remoteConfig.getFeatureFlags("user2")

        assertTrue(result1?.isNotEmpty() == true)
        assertTrue(result2?.isNotEmpty() == true)
        assertEquals(2, mockServer.requestCount)

        mockServer.shutdown()
    }

    @Test
    fun `cache handles different parameter combinations`() {
        val flagsResponse = createFlagsResponse("test-flag")

        val mockServer =
            createMockHttp(
                jsonResponse(flagsResponse),
                jsonResponse(flagsResponse),
            )
        val url = mockServer.url("/")

        val config = createTestConfig(host = url.toString())
        val api = PostHogApi(config)
        val remoteConfig = PostHogFeatureFlags(config, api, 60000, 100)

        // Test with all parameters
        val result1 =
            remoteConfig.getFeatureFlags(
                distinctId = "test-user",
                groups = mapOf("org" to "test-org"),
                personProperties = mapOf("plan" to "premium"),
                groupProperties = mapOf("test-org" to mapOf("size" to "large")),
            )

        // Test with null parameters (different cache key)
        val result2 =
            remoteConfig.getFeatureFlags(
                distinctId = "test-user",
                groups = null,
                personProperties = null,
                groupProperties = null,
            )

        assertTrue(result1?.isNotEmpty() == true)
        assertTrue(result2?.isNotEmpty() == true)
        assertEquals(
            2,
            mockServer.requestCount,
        ) // Should have made 2 API calls for different cache keys

        mockServer.shutdown()
    }

    @Test
    fun `getFeatureFlag handles different value types correctly`() {
        // Need to manually construct this one since we need different variants
        val customFlagsResponse =
            """
            {
                "flags": {
                    "string-flag": {
                        "key": "string-flag",
                        "enabled": true,
                        "variant": "string-value",
                        "metadata": { "version": 1, "payload": null, "id": 1 },
                        "reason": { "kind": "condition_match", "condition_match_type": "Test", "condition_index": 0 }
                    },
                    "boolean-flag": {
                        "key": "boolean-flag",
                        "enabled": true,
                        "variant": null,
                        "metadata": { "version": 1, "payload": null, "id": 1 },
                        "reason": { "kind": "condition_match", "condition_match_type": "Test", "condition_index": 0 }
                    },
                    "disabled-flag": {
                        "key": "disabled-flag",
                        "enabled": false,
                        "variant": null,
                        "metadata": { "version": 1, "payload": null, "id": 1 },
                        "reason": { "kind": "condition_match", "condition_match_type": "Test", "condition_index": 0 }
                    }
                }
            }
            """.trimIndent()

        val mockServer = createMockHttp(jsonResponse(customFlagsResponse))
        val url = mockServer.url("/")

        val config = createTestConfig(host = url.toString())
        val api = PostHogApi(config)
        val remoteConfig = PostHogFeatureFlags(config, api, 60000, 100)

        val stringResult =
            remoteConfig.getFeatureFlag("string-flag", "default", "test-user")
        val booleanResult =
            remoteConfig.getFeatureFlag("boolean-flag", false, "test-user")
        val disabledResult =
            remoteConfig.getFeatureFlag("disabled-flag", true, "test-user")

        assertEquals("string-value", stringResult)
        assertEquals(true, booleanResult)
        assertEquals(false, disabledResult)

        mockServer.shutdown()
    }

    @Test
    fun `local evaluation poller loads flag definitions`() {
        val logger = TestLogger()
        val localEvalResponse =
            createLocalEvaluationResponse(
                flagKey = "test-flag",
                aggregationGroupTypeIndex = null,
            )

        val mockServer =
            createMockHttp(
                jsonResponse(localEvalResponse),
            )
        val url = mockServer.url("/")

        val config = createTestConfig(logger, url.toString())
        val api = PostHogApi(config)
        val loadedLatch = CountDownLatch(1)
        val remoteConfig =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollIntervalSeconds = 30,
                onFeatureFlags = { loadedLatch.countDown() },
            )

        // Wait for poller to call onFeatureFlags callback
        val loaded = loadedLatch.await(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue(loaded, "Expected onFeatureFlags callback to be called by poller")

        // Check that we made the API call
        assertTrue(
            mockServer.requestCount >= 1,
            "Expected at least 1 request, got ${mockServer.requestCount}",
        )
        assertTrue(logger.containsLog("Loading feature flags for local evaluation"))
        assertTrue(logger.containsLog("Loaded 1 feature flags for local evaluation"))

        remoteConfig.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `local evaluation carries has_experiment from flag definitions into flag details`() {
        val localEvalResponse =
            createLocalEvaluationResponse(
                flagKey = "experiment-flag",
                hasExperiment = true,
            )

        val mockServer = createMockHttp(jsonResponse(localEvalResponse))
        val url = mockServer.url("/")

        val config = createTestConfig(host = url.toString())
        val api = PostHogApi(config)
        val loadedLatch = CountDownLatch(1)
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollIntervalSeconds = 30,
                onFeatureFlags = { loadedLatch.countDown() },
            )
        assertTrue(loadedLatch.await(5000, java.util.concurrent.TimeUnit.MILLISECONDS))

        val result =
            featureFlags.evaluateFlags(
                distinctId = "test-user",
                groups = null,
                personProperties = null,
                groupProperties = null,
                flagKeys = null,
                onlyEvaluateLocally = true,
                disableGeoip = false,
            )

        assertEquals(true, result.flags["experiment-flag"]?.metadata?.hasExperiment)

        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `local evaluation leaves has_experiment null when definitions omit it`() {
        val localEvalResponse = createLocalEvaluationResponse(flagKey = "plain-flag")

        val mockServer = createMockHttp(jsonResponse(localEvalResponse))
        val url = mockServer.url("/")

        val config = createTestConfig(host = url.toString())
        val api = PostHogApi(config)
        val loadedLatch = CountDownLatch(1)
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollIntervalSeconds = 30,
                onFeatureFlags = { loadedLatch.countDown() },
            )
        assertTrue(loadedLatch.await(5000, java.util.concurrent.TimeUnit.MILLISECONDS))

        val result =
            featureFlags.evaluateFlags(
                distinctId = "test-user",
                groups = null,
                personProperties = null,
                groupProperties = null,
                flagKeys = null,
                onlyEvaluateLocally = true,
                disableGeoip = false,
            )

        assertNull(result.flags.getValue("plain-flag").metadata.hasExperiment)

        featureFlags.shutDown()
        mockServer.shutdown()
    }

    /**
     * Local-evaluation definitions with one flag that resolves locally (no property filters) and one
     * that is inconclusive locally (a person-property filter with no value supplied).
     */
    private fun localEvalResponseWithResolvableAndInconclusiveFlags(): String =
        """
        {
            "flags": [
                {
                    "id": 1,
                    "name": "resolves-locally",
                    "key": "resolves-locally",
                    "active": true,
                    "filters": {
                        "groups": [
                            { "properties": [], "rollout_percentage": 100 }
                        ]
                    },
                    "version": 1
                },
                {
                    "id": 2,
                    "name": "needs-server",
                    "key": "needs-server",
                    "active": true,
                    "filters": {
                        "groups": [
                            {
                                "properties": [
                                    {
                                        "key": "email",
                                        "operator": "exact",
                                        "value": "match@example.com",
                                        "type": "person"
                                    }
                                ],
                                "rollout_percentage": 100
                            }
                        ]
                    },
                    "version": 1
                }
            ],
            "group_type_mapping": {},
            "cohorts": {}
        }
        """.trimIndent()

    private fun localEvalFeatureFlags(
        mockServer: MockWebServer,
        loadedLatch: CountDownLatch,
    ): PostHogFeatureFlags {
        val config = createTestConfig(host = mockServer.url("/").toString())
        return PostHogFeatureFlags(
            config,
            PostHogApi(config),
            60000,
            100,
            localEvaluation = true,
            personalApiKey = "test-personal-key",
            pollIntervalSeconds = 3600,
            onFeatureFlags = { loadedLatch.countDown() },
        )
    }

    private fun manuallyLoadedFeatureFlags(
        mockServer: MockWebServer,
        missingFlagKeysMaxSize: Int = 1_000,
        missingFlagProbeWaitTimeoutMs: Long = 10_000,
    ): PostHogFeatureFlags {
        val config = createTestConfig(host = mockServer.url("/").toString())
        return PostHogFeatureFlags(
            config,
            PostHogApi(config),
            60000,
            100,
            localEvaluation = true,
            personalApiKey = "test-personal-key",
            pollerEnabled = false,
            missingFlagKeysMaxSize = missingFlagKeysMaxSize,
            missingFlagProbeWaitTimeoutMs = missingFlagProbeWaitTimeoutMs,
        ).also { it.loadFeatureFlagDefinitions() }
    }

    private fun evaluateMissingFlag(
        featureFlags: PostHogFeatureFlags,
        distinctId: String,
        onlyEvaluateLocally: Boolean = false,
        missingKey: String = "missing-flag",
    ): EvaluateFlagsResult =
        featureFlags.evaluateFlags(
            distinctId = distinctId,
            groups = null,
            personProperties = null,
            groupProperties = null,
            flagKeys = listOf("known-flag", missingKey),
            onlyEvaluateLocally = onlyEvaluateLocally,
            disableGeoip = false,
        )

    @Test
    fun `empty flagKeys skips caches local definitions and remote work while other scopes still evaluate`() {
        val dispatcher =
            CountingDispatcher(
                {
                    jsonResponse(
                        createLocalEvaluationResponseFrom(
                            conclusiveFlagDefinition("first-flag"),
                            conclusiveFlagDefinition("second-flag"),
                        ),
                    )
                },
                { jsonResponse(createFlagsResponse("unexpected", enabled = true)) },
            )
        val mockServer = MockWebServer()
        mockServer.dispatcher = dispatcher
        mockServer.start()

        val config = createTestConfig(host = mockServer.url("/").toString())
        val definitionCache = TestFlagDefinitionCacheProvider(shouldFetch = true)
        val featureFlags =
            PostHogFeatureFlags(
                config,
                PostHogApi(config),
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollerEnabled = false,
                flagDefinitionCacheProvider = definitionCache,
            )

        val empty =
            featureFlags.evaluateFlags(
                distinctId = "user-1",
                groups = null,
                personProperties = null,
                groupProperties = null,
                flagKeys = emptyList(),
                onlyEvaluateLocally = false,
                disableGeoip = false,
            )

        assertTrue(empty.flags.isEmpty())
        assertTrue(empty.locallyEvaluated.isEmpty())
        assertNull(empty.requestId)
        assertNull(empty.evaluatedAt)
        assertNull(empty.definitionsLoadedAt)
        assertNull(empty.responseError)
        assertEquals(0, definitionCache.shouldFetchCalls, "must not consult the definition cache")
        assertEquals(0, definitionCache.getCalls, "must not read cached definitions")
        assertEquals(0, dispatcher.localEvaluationCalls.get(), "must not load local definitions")
        assertEquals(0, dispatcher.flagsCalls.get(), "must not consult /flags")
        assertEquals(0, mockServer.requestCount, "must do no request-time network work")

        val all =
            featureFlags.evaluateFlags(
                distinctId = "user-1",
                groups = null,
                personProperties = null,
                groupProperties = null,
                flagKeys = null,
                onlyEvaluateLocally = false,
                disableGeoip = false,
            )
        val scoped =
            featureFlags.evaluateFlags(
                distinctId = "user-2",
                groups = null,
                personProperties = null,
                groupProperties = null,
                flagKeys = listOf("second-flag"),
                onlyEvaluateLocally = false,
                disableGeoip = false,
            )

        assertEquals(setOf("first-flag", "second-flag"), all.flags.keys, "null still evaluates all flags")
        assertEquals(setOf("second-flag"), scoped.flags.keys, "a non-empty list remains exactly scoped")
        assertEquals(1, definitionCache.shouldFetchCalls, "null uses the normal definition-cache path")
        assertEquals(1, dispatcher.localEvaluationCalls.get(), "definitions load once through the normal path")
        assertEquals(0, dispatcher.flagsCalls.get(), "both normal-path evaluations resolve locally")

        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `evaluateFlags forwards the original scope and keeps locally resolved flags`() {
        val mockServer =
            createMockHttp(
                jsonResponse(localEvalResponseWithResolvableAndInconclusiveFlags()),
                jsonResponse(createFlagsResponse("needs-server", enabled = true)),
            )
        val loadedLatch = CountDownLatch(1)
        val featureFlags = localEvalFeatureFlags(mockServer, loadedLatch)
        assertTrue(loadedLatch.await(5000, java.util.concurrent.TimeUnit.MILLISECONDS))

        val result =
            featureFlags.evaluateFlags(
                distinctId = "user-1",
                groups = null,
                personProperties = null,
                groupProperties = null,
                flagKeys = listOf("resolves-locally", "needs-server"),
                onlyEvaluateLocally = false,
                disableGeoip = false,
            )

        // The flag that resolved in-process survives instead of being discarded on the first miss...
        assertEquals(true, result.flags["resolves-locally"]?.enabled)
        assertEquals(true, result.locallyEvaluated["resolves-locally"])
        // ...and the inconclusive flag is filled in from the /flags response.
        assertEquals(true, result.flags["needs-server"]?.enabled)
        assertEquals(false, result.locallyEvaluated["needs-server"])

        // A cached raw remote response is merged with a fresh local pass in the same way.
        val cached =
            featureFlags.evaluateFlags(
                distinctId = "user-1",
                groups = null,
                personProperties = null,
                groupProperties = null,
                flagKeys = listOf("resolves-locally", "needs-server"),
                onlyEvaluateLocally = false,
                disableGeoip = false,
            )
        assertEquals(true, cached.locallyEvaluated["resolves-locally"])
        assertEquals(false, cached.locallyEvaluated["needs-server"])
        assertEquals(2, mockServer.requestCount)

        mockServer.takeRequest() // local_evaluation request
        val flagsRequestBody = mockServer.takeRequest().body.unGzip()
        assertTrue(flagsRequestBody.contains("\"needs-server\""))
        assertTrue(flagsRequestBody.contains("\"resolves-locally\""))

        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `evaluateFlags fetches requested keys missing from local definitions`() {
        val mockServer =
            createMockHttp(
                jsonResponse(localEvalResponseWithResolvableAndInconclusiveFlags()),
                jsonResponse(createFlagsResponse("remote-only", enabled = true)),
            )
        val loadedLatch = CountDownLatch(1)
        val featureFlags = localEvalFeatureFlags(mockServer, loadedLatch)
        assertTrue(loadedLatch.await(5000, java.util.concurrent.TimeUnit.MILLISECONDS))

        val result =
            featureFlags.evaluateFlags(
                distinctId = "user-1",
                groups = null,
                personProperties = null,
                groupProperties = null,
                flagKeys = listOf("resolves-locally", "remote-only"),
                onlyEvaluateLocally = false,
                disableGeoip = false,
            )

        assertEquals(true, result.flags["resolves-locally"]?.enabled)
        assertEquals(true, result.locallyEvaluated["resolves-locally"])
        assertEquals(true, result.flags["remote-only"]?.enabled)
        assertEquals(false, result.locallyEvaluated["remote-only"])

        mockServer.takeRequest() // local_evaluation request
        val flagsRequestBody = mockServer.takeRequest().body.unGzip()
        assertTrue(flagsRequestBody.contains("\"remote-only\""))
        assertTrue(flagsRequestBody.contains("\"resolves-locally\""))
        assertFalse(flagsRequestBody.contains("\"needs-server\""))

        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `a failed partial fallback does not suppress the legacy retry`() {
        val mockServer =
            createMockHttp(
                jsonResponse(localEvalResponseWithResolvableAndInconclusiveFlags()),
                errorResponse(500, "Internal Server Error"),
                jsonResponse(createFlagsResponse("needs-server", enabled = true)),
            )
        val loadedLatch = CountDownLatch(1)
        val featureFlags = localEvalFeatureFlags(mockServer, loadedLatch)
        assertTrue(loadedLatch.await(5000, java.util.concurrent.TimeUnit.MILLISECONDS))

        val evaluation =
            featureFlags.evaluateFlags(
                distinctId = "user-1",
                groups = null,
                personProperties = null,
                groupProperties = null,
                flagKeys = null,
                onlyEvaluateLocally = false,
                disableGeoip = false,
            )
        assertEquals(setOf("resolves-locally"), evaluation.flags.keys)

        val legacyFlags =
            featureFlags.getFeatureFlags(
                distinctId = "user-1",
                groups = null,
                personProperties = null,
                groupProperties = null,
            )
        assertEquals(setOf("needs-server"), legacyFlags?.keys)
        assertEquals(3, mockServer.requestCount, "legacy evaluation must retry the failed fallback")

        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `successful partial fallback caches the raw remote response for legacy callers`() {
        val mockServer =
            createMockHttp(
                jsonResponse(localEvalResponseWithResolvableAndInconclusiveFlags()),
                jsonResponse(
                    createMultipleFlagsResponse(
                        "resolves-locally" to false,
                        "needs-server" to true,
                    ),
                ),
            )
        val loadedLatch = CountDownLatch(1)
        val featureFlags = localEvalFeatureFlags(mockServer, loadedLatch)
        assertTrue(loadedLatch.await(5000, java.util.concurrent.TimeUnit.MILLISECONDS))

        val evaluation =
            featureFlags.evaluateFlags(
                distinctId = "user-1",
                groups = null,
                personProperties = null,
                groupProperties = null,
                flagKeys = null,
                onlyEvaluateLocally = false,
                disableGeoip = false,
            )
        assertEquals(true, evaluation.flags["resolves-locally"]?.enabled)
        assertEquals(true, evaluation.flags["needs-server"]?.enabled)

        val legacyFlags =
            featureFlags.getFeatureFlags(
                distinctId = "user-1",
                groups = null,
                personProperties = null,
                groupProperties = null,
            )
        assertEquals(false, legacyFlags?.get("resolves-locally")?.enabled)
        assertEquals(true, legacyFlags?.get("needs-server")?.enabled)
        assertEquals(2, mockServer.requestCount, "legacy evaluation must reuse the raw remote entry")

        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `poller does not start when pollerEnabled is false`() {
        val logger = TestLogger()
        val localEvalResponse =
            createLocalEvaluationResponse(
                flagKey = "test-flag",
                aggregationGroupTypeIndex = null,
            )

        val mockServer =
            createMockHttp(
                jsonResponse(localEvalResponse),
            )
        val url = mockServer.url("/")

        val config = createTestConfig(logger, url.toString())
        val api = PostHogApi(config)
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollIntervalSeconds = 1,
                pollerEnabled = false,
            )

        // Wait to ensure poller doesn't start
        Thread.sleep(1000)

        // Verify poller did NOT start (no automatic API calls)
        assertEquals(
            0,
            mockServer.requestCount,
            "Expected poller to not start (0 requests), but got ${mockServer.requestCount}",
        )
        assertFalse(logger.containsLog("Loading feature flags for local evaluation"))

        // Manual load should still work
        featureFlags.loadFeatureFlagDefinitions()
        assertEquals(1, mockServer.requestCount, "Manual load should work when poller is disabled")
        assertTrue(logger.containsLog("Loading feature flags for local evaluation"))

        mockServer.shutdown()
    }

    @Test
    fun `loadFeatureFlagDefinitions no-ops and logs without personal api key`() {
        val logger = TestLogger()
        val mockServer = createMockHttp(jsonResponse(createLocalEvaluationResponse("test-flag")))
        val url = mockServer.url("/")

        val config = createTestConfig(logger, url.toString())
        val api = PostHogApi(config)
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = null,
                pollerEnabled = false,
            )

        featureFlags.loadFeatureFlagDefinitions()

        assertEquals(0, mockServer.requestCount)
        assertTrue(logger.containsLog("Local evaluation requires a personal API key"))

        mockServer.shutdown()
    }

    @Test
    fun `evaluateFlags local only no-ops and logs without personal api key`() {
        val logger = TestLogger()
        val mockServer = createMockHttp(jsonResponse(createFlagsResponse("test-flag")))
        val url = mockServer.url("/")

        val config = createTestConfig(logger, url.toString())
        val api = PostHogApi(config)
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = false,
                personalApiKey = null,
            )

        val result =
            featureFlags.evaluateFlags(
                distinctId = "test-user",
                groups = null,
                personProperties = null,
                groupProperties = null,
                flagKeys = null,
                onlyEvaluateLocally = true,
                disableGeoip = false,
            )

        assertTrue(result.flags.isEmpty())
        assertEquals(0, mockServer.requestCount)
        assertTrue(logger.containsLog("Local evaluation requires a personal API key"))

        mockServer.shutdown()
    }

    @Test
    fun `evaluateFlags falls back for requested keys with no local definition`() {
        val logger = TestLogger()
        val mockServer =
            createMockHttp(
                jsonResponse(createLocalEvaluationResponse("known-flag")),
                jsonResponse(createFlagsResponse("typo-flag", enabled = true)),
            )
        val url = mockServer.url("/")

        val config = createTestConfig(logger, url.toString())
        val api = PostHogApi(config)
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollerEnabled = false,
            )

        val result =
            featureFlags.evaluateFlags(
                distinctId = "user-123",
                groups = null,
                personProperties = null,
                groupProperties = null,
                flagKeys = listOf("known-flag", "typo-flag"),
                onlyEvaluateLocally = false,
                disableGeoip = false,
            )

        assertEquals(setOf("known-flag", "typo-flag"), result.flags.keys)
        assertTrue(logger.containsLog("No local definition for requested flag(s) typo-flag"))
        assertEquals(2, mockServer.requestCount, "the undefined key falls back to /flags")

        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `clean remote omission suppresses missing key probes across identities`() {
        val mockServer =
            createMockHttp(
                jsonResponse(createLocalEvaluationResponse("known-flag")),
                jsonResponse(createEmptyFlagsResponse()),
            )
        val featureFlags = manuallyLoadedFeatureFlags(mockServer)

        assertEquals(setOf("known-flag"), evaluateMissingFlag(featureFlags, "user-1").flags.keys)
        assertEquals(setOf("known-flag"), evaluateMissingFlag(featureFlags, "user-2").flags.keys)
        assertEquals(2, mockServer.requestCount, "only the definitions load and first probe are allowed")

        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `missing key knowledge evicts at capacity`() {
        val mockServer =
            createMockHttp(
                jsonResponse(createLocalEvaluationResponse("known-flag")),
                jsonResponse(createEmptyFlagsResponse()),
                jsonResponse(createEmptyFlagsResponse()),
                jsonResponse(createEmptyFlagsResponse()),
            )
        val featureFlags = manuallyLoadedFeatureFlags(mockServer, missingFlagKeysMaxSize = 1)

        evaluateMissingFlag(featureFlags, "user-1", missingKey = "missing-a")
        evaluateMissingFlag(featureFlags, "user-2", missingKey = "missing-b")
        evaluateMissingFlag(featureFlags, "user-3", missingKey = "missing-a")

        assertEquals(4, mockServer.requestCount, "the evicted key must become probe-eligible")
        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `mixed scope positive response clears retained omission`() {
        val responseNumber = AtomicInteger(0)
        val dispatcher =
            CountingDispatcher(
                { jsonResponse(createLocalEvaluationResponse("known-flag")) },
                {
                    when (responseNumber.incrementAndGet()) {
                        1 -> jsonResponse(createEmptyFlagsResponse())
                        2 ->
                            jsonResponse(
                                createMultipleFlagsResponse(
                                    "previously-missing" to true,
                                    "other-missing" to true,
                                ),
                            )
                        else -> jsonResponse(createFlagsResponse("previously-missing", enabled = false))
                    }
                },
            )
        val mockServer =
            MockWebServer().apply {
                this.dispatcher = dispatcher
                start()
            }
        val featureFlags = manuallyLoadedFeatureFlags(mockServer)

        evaluateMissingFlag(featureFlags, "user-1", missingKey = "previously-missing")
        val mixed =
            featureFlags.evaluateFlags(
                distinctId = "user-2",
                groups = null,
                personProperties = null,
                groupProperties = null,
                flagKeys = listOf("known-flag", "previously-missing", "other-missing"),
                onlyEvaluateLocally = false,
                disableGeoip = false,
            )
        val afterPositive = evaluateMissingFlag(featureFlags, "user-3", missingKey = "previously-missing")

        assertEquals(true, mixed.flags["previously-missing"]?.enabled)
        assertEquals(false, afterPositive.flags["previously-missing"]?.enabled)
        assertEquals(3, dispatcher.flagsCalls.get(), "positive evidence must permit the next evaluation")
        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `unscoped positive response clears retained omission`() {
        val responseNumber = AtomicInteger(0)
        val dispatcher =
            CountingDispatcher(
                { jsonResponse(localEvalResponseWithResolvableAndInconclusiveFlags()) },
                {
                    when (responseNumber.incrementAndGet()) {
                        1 -> jsonResponse(createEmptyFlagsResponse())
                        2 ->
                            jsonResponse(
                                createMultipleFlagsResponse(
                                    "previously-missing" to true,
                                    "needs-server" to true,
                                ),
                            )
                        else -> jsonResponse(createFlagsResponse("previously-missing", enabled = false))
                    }
                },
            )
        val mockServer =
            MockWebServer().apply {
                this.dispatcher = dispatcher
                start()
            }
        val featureFlags = manuallyLoadedFeatureFlags(mockServer)

        featureFlags.evaluateFlags(
            distinctId = "user-1",
            groups = null,
            personProperties = null,
            groupProperties = null,
            flagKeys = listOf("resolves-locally", "previously-missing"),
            onlyEvaluateLocally = false,
            disableGeoip = false,
        )
        val unscoped =
            featureFlags.evaluateFlags(
                distinctId = "user-2",
                groups = null,
                personProperties = null,
                groupProperties = null,
                flagKeys = null,
                onlyEvaluateLocally = false,
                disableGeoip = false,
            )
        val afterPositive =
            featureFlags.evaluateFlags(
                distinctId = "user-3",
                groups = null,
                personProperties = null,
                groupProperties = null,
                flagKeys = listOf("previously-missing"),
                onlyEvaluateLocally = false,
                disableGeoip = false,
            )

        assertEquals(true, unscoped.flags["previously-missing"]?.enabled)
        assertEquals(false, afterPositive.flags["previously-missing"]?.enabled)
        assertEquals(3, dispatcher.flagsCalls.get(), "unscoped positive evidence must permit the next evaluation")
        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `delayed non-owned omission does not overwrite newer positive evidence`() {
        val delayedProbeStarted = CountDownLatch(1)
        val releaseDelayedProbe = CountDownLatch(1)
        val responseNumber = AtomicInteger(0)
        val dispatcher =
            CountingDispatcher(
                { jsonResponse(createLocalEvaluationResponse("known-flag")) },
                {
                    when (responseNumber.incrementAndGet()) {
                        1 -> jsonResponse(createEmptyFlagsResponse())
                        2 -> {
                            delayedProbeStarted.countDown()
                            releaseDelayedProbe.await()
                            jsonResponse(createEmptyFlagsResponse())
                        }
                        3 ->
                            jsonResponse(
                                createMultipleFlagsResponse(
                                    "previously-missing" to true,
                                    "missing-b" to true,
                                ),
                            )
                        else -> jsonResponse(createFlagsResponse("previously-missing", enabled = false))
                    }
                },
            )
        val mockServer =
            MockWebServer().apply {
                this.dispatcher = dispatcher
                start()
            }
        val featureFlags = manuallyLoadedFeatureFlags(mockServer)

        evaluateMissingFlag(featureFlags, "user-1", missingKey = "previously-missing")
        val delayed =
            Thread {
                featureFlags.evaluateFlags(
                    distinctId = "user-2",
                    groups = null,
                    personProperties = null,
                    groupProperties = null,
                    flagKeys = listOf("known-flag", "previously-missing", "missing-a"),
                    onlyEvaluateLocally = false,
                    disableGeoip = false,
                )
            }.also { it.start() }
        assertTrue(delayedProbeStarted.await(5, TimeUnit.SECONDS))

        val positive =
            featureFlags.evaluateFlags(
                distinctId = "user-3",
                groups = null,
                personProperties = null,
                groupProperties = null,
                flagKeys = listOf("known-flag", "previously-missing", "missing-b"),
                onlyEvaluateLocally = false,
                disableGeoip = false,
            )
        releaseDelayedProbe.countDown()
        delayed.join(5_000)
        val afterPositive = evaluateMissingFlag(featureFlags, "user-4", missingKey = "previously-missing")

        assertEquals(true, positive.flags["previously-missing"]?.enabled)
        assertFalse(delayed.isAlive)
        assertEquals(false, afterPositive.flags["previously-missing"]?.enabled)
        assertEquals(4, dispatcher.flagsCalls.get())
        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `concurrent missing key calls share one clean probe`() {
        val firstProbeStarted = CountDownLatch(1)
        val releaseProbe = CountDownLatch(1)
        val duplicateProbe = CountDownLatch(1)
        val responseNumber = AtomicInteger(0)
        val dispatcher =
            CountingDispatcher(
                { jsonResponse(createLocalEvaluationResponse("known-flag")) },
                {
                    if (responseNumber.incrementAndGet() == 1) {
                        firstProbeStarted.countDown()
                    } else {
                        duplicateProbe.countDown()
                    }
                    releaseProbe.await()
                    jsonResponse(createEmptyFlagsResponse())
                },
            )
        val mockServer =
            MockWebServer().apply {
                this.dispatcher = dispatcher
                start()
            }
        val featureFlags = manuallyLoadedFeatureFlags(mockServer)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val owner = Thread { runCatching { evaluateMissingFlag(featureFlags, "owner") }.exceptionOrNull()?.let(errors::add) }
        owner.start()
        assertTrue(firstProbeStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))

        val entered = CountDownLatch(10)
        val waiters =
            (1..10).map { index ->
                Thread {
                    entered.countDown()
                    runCatching { evaluateMissingFlag(featureFlags, "waiter-$index") }.exceptionOrNull()?.let(errors::add)
                }.also { it.start() }
            }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val sawDuplicate = duplicateProbe.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        releaseProbe.countDown()
        (waiters + owner).forEach { it.join(5_000) }

        assertFalse(sawDuplicate)
        assertTrue(errors.isEmpty(), "unexpected errors: $errors")
        assertTrue((waiters + owner).none { it.isAlive })
        assertEquals(1, dispatcher.flagsCalls.get())
        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `interrupted waiter does not start a duplicate probe`() {
        val firstProbeStarted = CountDownLatch(1)
        val releaseFirstProbe = CountDownLatch(1)
        val duplicateProbe = CountDownLatch(1)
        val responseNumber = AtomicInteger(0)
        val dispatcher =
            CountingDispatcher(
                { jsonResponse(createLocalEvaluationResponse("known-flag")) },
                {
                    if (responseNumber.incrementAndGet() == 1) {
                        firstProbeStarted.countDown()
                        releaseFirstProbe.await()
                    } else {
                        duplicateProbe.countDown()
                    }
                    jsonResponse(createEmptyFlagsResponse())
                },
            )
        val mockServer =
            MockWebServer().apply {
                this.dispatcher = dispatcher
                start()
            }
        val featureFlags = manuallyLoadedFeatureFlags(mockServer)
        val owner = Thread { evaluateMissingFlag(featureFlags, "owner") }.also { it.start() }
        assertTrue(firstProbeStarted.await(5, TimeUnit.SECONDS))
        val waiterEntered = CountDownLatch(1)
        val waiter =
            Thread {
                waiterEntered.countDown()
                evaluateMissingFlag(featureFlags, "waiter")
            }.also { it.start() }
        assertTrue(waiterEntered.await(5, TimeUnit.SECONDS))
        Thread.sleep(100)

        waiter.interrupt()
        waiter.join(5_000)
        val startedDuplicate = duplicateProbe.await(500, TimeUnit.MILLISECONDS)
        releaseFirstProbe.countDown()
        owner.join(5_000)

        assertFalse(startedDuplicate)
        assertFalse(waiter.isAlive)
        assertFalse(owner.isAlive)
        assertEquals(1, dispatcher.flagsCalls.get())
        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `timed out waiter does not block indefinitely or start a duplicate probe`() {
        val firstProbeStarted = CountDownLatch(1)
        val releaseFirstProbe = CountDownLatch(1)
        val duplicateProbe = CountDownLatch(1)
        val responseNumber = AtomicInteger(0)
        val dispatcher =
            CountingDispatcher(
                { jsonResponse(createLocalEvaluationResponse("known-flag")) },
                {
                    if (responseNumber.incrementAndGet() == 1) {
                        firstProbeStarted.countDown()
                        releaseFirstProbe.await()
                    } else {
                        duplicateProbe.countDown()
                    }
                    jsonResponse(createEmptyFlagsResponse())
                },
            )
        val mockServer =
            MockWebServer().apply {
                this.dispatcher = dispatcher
                start()
            }
        val featureFlags = manuallyLoadedFeatureFlags(mockServer, missingFlagProbeWaitTimeoutMs = 100)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val owner =
            Thread {
                runCatching { evaluateMissingFlag(featureFlags, "owner") }.exceptionOrNull()?.let(errors::add)
            }.also { it.start() }
        assertTrue(firstProbeStarted.await(5, TimeUnit.SECONDS))
        val waiter =
            Thread {
                runCatching { evaluateMissingFlag(featureFlags, "waiter") }.exceptionOrNull()?.let(errors::add)
            }.also { it.start() }

        waiter.join(5_000)
        val ownerWasStillInFlight = owner.isAlive
        val startedDuplicate = duplicateProbe.await(500, TimeUnit.MILLISECONDS)
        releaseFirstProbe.countDown()
        owner.join(5_000)

        assertFalse(waiter.isAlive)
        assertTrue(ownerWasStillInFlight)
        assertFalse(startedDuplicate)
        assertFalse(owner.isAlive)
        assertTrue(errors.isEmpty(), "unexpected errors: $errors")
        assertEquals(1, dispatcher.flagsCalls.get())
        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `unrelated missing keys probe concurrently`() {
        val probesStarted = CountDownLatch(2)
        val releaseProbes = CountDownLatch(1)
        val dispatcher =
            CountingDispatcher(
                { jsonResponse(createLocalEvaluationResponse("known-flag")) },
                {
                    probesStarted.countDown()
                    releaseProbes.await()
                    jsonResponse(createEmptyFlagsResponse())
                },
            )
        val mockServer =
            MockWebServer().apply {
                this.dispatcher = dispatcher
                start()
            }
        val featureFlags = manuallyLoadedFeatureFlags(mockServer)
        val callers =
            listOf("missing-a", "missing-b").map { key ->
                Thread { evaluateMissingFlag(featureFlags, "user-$key", missingKey = key) }.also { it.start() }
            }

        val ranConcurrently = probesStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)
        releaseProbes.countDown()
        callers.forEach { it.join(5_000) }

        assertTrue(ranConcurrently)
        assertTrue(callers.none { it.isAlive })
        assertEquals(2, dispatcher.flagsCalls.get())
        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `failed owner coalesces waiters behind one retry`() {
        val firstProbeStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val retryStarted = CountDownLatch(1)
        val extraRetry = CountDownLatch(1)
        val releaseRetry = CountDownLatch(1)
        val responseNumber = AtomicInteger(0)
        val dispatcher =
            CountingDispatcher(
                { jsonResponse(createLocalEvaluationResponse("known-flag")) },
                {
                    when (responseNumber.incrementAndGet()) {
                        1 -> {
                            firstProbeStarted.countDown()
                            releaseFirst.await()
                            errorResponse(500, "Internal Server Error")
                        }
                        2 -> {
                            retryStarted.countDown()
                            releaseRetry.await()
                            jsonResponse(createEmptyFlagsResponse())
                        }
                        else -> {
                            extraRetry.countDown()
                            jsonResponse(createEmptyFlagsResponse())
                        }
                    }
                },
            )
        val mockServer =
            MockWebServer().apply {
                this.dispatcher = dispatcher
                start()
            }
        val featureFlags = manuallyLoadedFeatureFlags(mockServer)
        val owner = Thread { evaluateMissingFlag(featureFlags, "failed-owner") }.also { it.start() }
        assertTrue(firstProbeStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val entered = CountDownLatch(8)
        val waiters =
            (1..8).map { index ->
                Thread {
                    entered.countDown()
                    evaluateMissingFlag(featureFlags, "retry-waiter-$index")
                }.also { it.start() }
            }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))

        releaseFirst.countDown()
        val retried = retryStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)
        val sawExtraRetry = extraRetry.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        releaseRetry.countDown()
        (waiters + owner).forEach { it.join(5_000) }

        assertTrue(retried)
        assertFalse(sawExtraRetry)
        assertTrue((waiters + owner).none { it.isAlive })
        assertEquals(2, dispatcher.flagsCalls.get())
        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `refresh invalidates an in-flight probe generation`() {
        val oldProbeStarted = CountDownLatch(1)
        val releaseOldProbe = CountDownLatch(1)
        val newProbeStarted = CountDownLatch(1)
        val releaseNewProbe = CountDownLatch(1)
        val flagsCalls = AtomicInteger(0)
        val definitions = createLocalEvaluationResponse("known-flag")
        val dispatcher =
            CountingDispatcher(
                { jsonResponse(definitions) },
                {
                    if (flagsCalls.incrementAndGet() == 1) {
                        oldProbeStarted.countDown()
                        releaseOldProbe.await()
                    } else {
                        newProbeStarted.countDown()
                        releaseNewProbe.await()
                    }
                    jsonResponse(createEmptyFlagsResponse())
                },
            )
        val mockServer =
            MockWebServer().apply {
                this.dispatcher = dispatcher
                start()
            }
        val featureFlags = manuallyLoadedFeatureFlags(mockServer)
        val owner = Thread { evaluateMissingFlag(featureFlags, "old-owner") }.also { it.start() }
        assertTrue(oldProbeStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val waiter = Thread { evaluateMissingFlag(featureFlags, "new-waiter") }.also { it.start() }

        featureFlags.loadFeatureFlagDefinitions()
        val newGenerationProbed = newProbeStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)
        releaseNewProbe.countDown()
        releaseOldProbe.countDown()
        owner.join(5_000)
        waiter.join(5_000)

        assertTrue(newGenerationProbed)
        assertTrue(owner.isAlive.not() && waiter.isAlive.not())
        assertEquals(2, flagsCalls.get())
        evaluateMissingFlag(featureFlags, "after-refresh")
        assertEquals(2, flagsCalls.get(), "the new generation should retain its clean omission")
        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `modified definitions refresh clears missing key suppression and bypasses identity cache`() {
        val definitions = createLocalEvaluationResponse("known-flag")
        val mockServer =
            createMockHttp(
                jsonResponse(definitions),
                jsonResponse(createEmptyFlagsResponse()),
                jsonResponse(definitions),
                jsonResponse(createEmptyFlagsResponse()),
            )
        val featureFlags = manuallyLoadedFeatureFlags(mockServer)

        evaluateMissingFlag(featureFlags, "user-1")
        featureFlags.loadFeatureFlagDefinitions()
        evaluateMissingFlag(featureFlags, "user-1")

        assertEquals(4, mockServer.requestCount, "the first call after refresh must make a new probe")

        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `not modified definitions refresh clears missing key suppression`() {
        val definitions = createLocalEvaluationResponse("known-flag")
        val mockServer =
            createMockHttp(
                jsonResponseWithEtag(definitions, "\"etag-v1\""),
                jsonResponse(createEmptyFlagsResponse()),
                notModifiedResponse("\"etag-v1\""),
                jsonResponse(createEmptyFlagsResponse()),
            )
        val featureFlags = manuallyLoadedFeatureFlags(mockServer)

        evaluateMissingFlag(featureFlags, "user-1")
        featureFlags.loadFeatureFlagDefinitions()
        evaluateMissingFlag(featureFlags, "user-1")

        assertEquals(4, mockServer.requestCount, "a 304 must permit a new probe")

        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `failed definitions refresh preserves missing key suppression`() {
        val mockServer =
            createMockHttp(
                jsonResponse(createLocalEvaluationResponse("known-flag")),
                jsonResponse(createEmptyFlagsResponse()),
                errorResponse(500, "Internal Server Error"),
                jsonResponse(createEmptyFlagsResponse()),
            )
        val featureFlags = manuallyLoadedFeatureFlags(mockServer)

        val first = evaluateMissingFlag(featureFlags, "user-1")
        featureFlags.loadFeatureFlagDefinitions()
        evaluateMissingFlag(featureFlags, "user-2")

        assertEquals(setOf("known-flag"), first.flags.keys)
        assertEquals(3, mockServer.requestCount, "a failed refresh must not permit another probe")

        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `failed and inconclusive responses allow the same identity to retry`() {
        val responses =
            listOf(
                "HTTP failure" to errorResponse(500, "Internal Server Error"),
                "quota limit" to jsonResponse(createFlagsResponseWithQuotaLimited()),
                "computation error" to jsonResponse(createFlagsResponseWithErrors()),
            )

        for ((name, firstResponse) in responses) {
            val mockServer =
                createMockHttp(
                    jsonResponse(createLocalEvaluationResponse("known-flag")),
                    firstResponse,
                    jsonResponse(createFlagsResponse("missing-flag", enabled = true)),
                )
            val featureFlags = manuallyLoadedFeatureFlags(mockServer)

            val first = evaluateMissingFlag(featureFlags, "user-1")
            val retried = evaluateMissingFlag(featureFlags, "user-1")

            assertFalse(first.flags.containsKey("missing-flag"))
            assertEquals(true, retried.flags["missing-flag"]?.enabled, "$name must not suppress the retry")
            assertEquals(3, mockServer.requestCount, "$name must allow the same identity to retry")
            featureFlags.shutDown()
            mockServer.shutdown()
        }
    }

    @Test
    fun `remotely returned key uses the identity cache until a clean response omits it`() {
        val mockServer =
            createMockHttp(
                jsonResponse(createLocalEvaluationResponse("known-flag")),
                jsonResponse(createFlagsResponse("remote-only", enabled = true)),
                jsonResponse(createEmptyFlagsResponse()),
            )
        val featureFlags = manuallyLoadedFeatureFlags(mockServer)

        val first = evaluateMissingFlag(featureFlags, "user-1", missingKey = "remote-only")
        val cached = evaluateMissingFlag(featureFlags, "user-1", missingKey = "remote-only")
        assertEquals(2, mockServer.requestCount, "the same identity should reuse the returned flag")

        val second = evaluateMissingFlag(featureFlags, "user-2", missingKey = "remote-only")
        val suppressed = evaluateMissingFlag(featureFlags, "user-3", missingKey = "remote-only")

        assertEquals(true, first.flags["remote-only"]?.enabled)
        assertEquals(true, cached.flags["remote-only"]?.enabled)
        assertFalse(second.flags.containsKey("remote-only"))
        assertFalse(suppressed.flags.containsKey("remote-only"))
        assertEquals(3, mockServer.requestCount, "the clean omission should suppress later probes")

        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `known remote and brand new keys share one scoped fallback`() {
        val mockServer =
            createMockHttp(
                jsonResponse(createLocalEvaluationResponse("known-flag")),
                jsonResponse(createFlagsResponse("remote-only", enabled = true)),
                jsonResponse(
                    createMultipleFlagsResponse(
                        "remote-only" to true,
                        "brand-new" to true,
                    ),
                ),
            )
        val featureFlags = manuallyLoadedFeatureFlags(mockServer)
        evaluateMissingFlag(featureFlags, "user-1", missingKey = "remote-only")

        val mixed =
            featureFlags.evaluateFlags(
                distinctId = "user-2",
                groups = null,
                personProperties = null,
                groupProperties = null,
                flagKeys = listOf("known-flag", "remote-only", "brand-new"),
                onlyEvaluateLocally = false,
                disableGeoip = false,
            )

        assertEquals(setOf("known-flag", "remote-only", "brand-new"), mixed.flags.keys)
        assertEquals(3, mockServer.requestCount, "both missing keys should share one fallback")

        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `shared definition cache refresh clears missing key suppression`() {
        val mockServer =
            createMockHttp(
                jsonResponse(createEmptyFlagsResponse()),
                jsonResponse(createEmptyFlagsResponse()),
            )
        val config = createTestConfig(host = mockServer.url("/").toString())
        val provider =
            TestFlagDefinitionCacheProvider(
                cacheData = createFlagDefinitionCacheData(config, "known-flag"),
                shouldFetch = false,
            )
        val featureFlags =
            PostHogFeatureFlags(
                config,
                PostHogApi(config),
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollerEnabled = false,
                flagDefinitionCacheProvider = provider,
            )

        featureFlags.loadFeatureFlagDefinitions()
        evaluateMissingFlag(featureFlags, "user-1")
        featureFlags.loadFeatureFlagDefinitions()
        evaluateMissingFlag(featureFlags, "user-1")

        assertEquals(2, mockServer.requestCount, "a shared-cache refresh must permit a new probe")

        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `local only evaluation omits missing requested keys without remote fallback`() {
        val mockServer = createMockHttp(jsonResponse(createLocalEvaluationResponse("known-flag")))
        val config = createTestConfig(host = mockServer.url("/").toString())
        val featureFlags =
            PostHogFeatureFlags(
                config,
                PostHogApi(config),
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollerEnabled = false,
            )

        val result =
            featureFlags.evaluateFlags(
                distinctId = "user-123",
                groups = null,
                personProperties = null,
                groupProperties = null,
                flagKeys = listOf("known-flag", "typo-flag"),
                onlyEvaluateLocally = true,
                disableGeoip = false,
            )

        assertEquals(setOf("known-flag"), result.flags.keys)
        assertEquals(1, mockServer.requestCount, "only the definitions load is allowed")

        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `definitions that fail to load are not re-fetched on every evaluateFlags call`() {
        // A personal API key that always fails never sets `definitionsLoaded`, so nothing but the
        // cached result stops every call re-attempting a blocking /local_evaluation load. The
        // poller is off so the only requests counted are the ones evaluateFlags itself makes.
        val dispatcher =
            CountingDispatcher(
                { errorResponse(401, "Unauthorized") },
                { jsonResponse(createFlagsResponse("remote-flag", enabled = true)) },
            )
        val mockServer = MockWebServer()
        mockServer.dispatcher = dispatcher
        mockServer.start()

        val config = createTestConfig(TestLogger(), mockServer.url("/").toString())
        val featureFlags =
            PostHogFeatureFlags(
                config,
                PostHogApi(config),
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollerEnabled = false,
            )

        fun evaluate(onlyEvaluateLocally: Boolean) =
            featureFlags.evaluateFlags(
                distinctId = "user-123",
                groups = null,
                personProperties = null,
                groupProperties = null,
                flagKeys = null,
                onlyEvaluateLocally = onlyEvaluateLocally,
                disableGeoip = false,
            )

        evaluate(onlyEvaluateLocally = false)
        val definitionRequestsAfterFirstCall = dispatcher.localEvaluationCalls.get()
        assertEquals(1, definitionRequestsAfterFirstCall)

        // Both modes must be shielded, since callers interleave them for one identity.
        repeat(4) {
            evaluate(onlyEvaluateLocally = true)
            evaluate(onlyEvaluateLocally = false)
        }

        assertEquals(
            definitionRequestsAfterFirstCall,
            dispatcher.localEvaluationCalls.get(),
            "repeat calls must not each re-attempt a blocking definitions load",
        )
        assertEquals(1, dispatcher.flagsCalls.get())

        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `onlyEvaluateLocally returns empty rather than cached remote flags when definitions are unavailable`() {
        // Skipping the definitions re-load must not turn into serving the cache entry, or a
        // local-only pass would answer with remote values.
        val dispatcher =
            CountingDispatcher(
                { errorResponse(401, "Unauthorized") },
                { jsonResponse(createFlagsResponse("remote-flag", enabled = true)) },
            )
        val mockServer = MockWebServer()
        mockServer.dispatcher = dispatcher
        mockServer.start()

        val config = createTestConfig(TestLogger(), mockServer.url("/").toString())
        val featureFlags =
            PostHogFeatureFlags(
                config,
                PostHogApi(config),
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollerEnabled = false,
            )

        val remote =
            featureFlags.evaluateFlags(
                distinctId = "user-123",
                groups = null,
                personProperties = null,
                groupProperties = null,
                flagKeys = null,
                onlyEvaluateLocally = false,
                disableGeoip = false,
            )
        assertEquals(setOf("remote-flag"), remote.flags.keys, "the cache now holds a remote success")

        val localOnly =
            featureFlags.evaluateFlags(
                distinctId = "user-123",
                groups = null,
                personProperties = null,
                groupProperties = null,
                flagKeys = null,
                onlyEvaluateLocally = true,
                disableGeoip = false,
            )

        assertTrue(localOnly.flags.isEmpty(), "a local-only snapshot must never carry remote values")
        assertEquals(
            1,
            dispatcher.localEvaluationCalls.get(),
            "the cache entry must also stop the local-only call re-attempting a definitions load",
        )

        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `poller starts when pollerEnabled is true (default)`() {
        val logger = TestLogger()
        val localEvalResponse =
            createLocalEvaluationResponse(
                flagKey = "test-flag",
                aggregationGroupTypeIndex = null,
            )

        val mockServer =
            createMockHttp(
                jsonResponse(localEvalResponse),
            )
        val url = mockServer.url("/")

        val config = createTestConfig(logger, url.toString())
        val api = PostHogApi(config)
        val loadedLatch = CountDownLatch(1)

        // Create with default pollerEnabled (should be true)
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollIntervalSeconds = 1,
                onFeatureFlags = { loadedLatch.countDown() },
                // pollerEnabled defaults to true - not specified
            )

        // Wait for poller to call onFeatureFlags callback
        val loaded = loadedLatch.await(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue(loaded, "Expected onFeatureFlags callback to be called by poller")

        // Verify poller started and made API call
        assertTrue(
            mockServer.requestCount >= 1,
            "Expected poller to start and make at least 1 request, got ${mockServer.requestCount}",
        )
        assertTrue(logger.containsLog("Loading feature flags for local evaluation"))

        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `group-based flag evaluates correctly when group is provided`() {
        val logger = TestLogger()
        val localEvalResponse =
            createLocalEvaluationResponse(
                flagKey = "org-feature",
                aggregationGroupTypeIndex = 2,
            )

        // Mock both local evaluation endpoint and regular flags endpoint
        val mockServer =
            createMockHttp(
                jsonResponse(localEvalResponse),
                jsonResponse(createEmptyFlagsResponse()),
            )
        val url = mockServer.url("/")

        val config = createTestConfig(logger, url.toString())
        val api = PostHogApi(config)
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
            )

        val result =
            featureFlags.getFeatureFlag(
                key = "org-feature",
                defaultValue = false,
                distinctId = "user-123",
                groups = mapOf("organization" to "org-456"),
                groupProperties = mapOf("org-456" to mapOf("plan" to "enterprise")),
            )

        // Debug logging
        if (result != true) {
            println("Logger output: ${logger.logs.joinToString("\n")}")
        }

        assertEquals(true, result)
        assertTrue(logger.containsLog("Local evaluation successful"))

        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `group-based flag returns false when required group is missing`() {
        val logger = TestLogger()
        val localEvalResponse =
            createLocalEvaluationResponse(
                flagKey = "org-feature",
                aggregationGroupTypeIndex = 2,
            )

        // Add fallback response in case local evaluation fails
        val mockServer =
            createMockHttp(
                jsonResponse(localEvalResponse),
                jsonResponse(createEmptyFlagsResponse()),
            )
        val url = mockServer.url("/")

        val config = createTestConfig(logger, url.toString())
        val api = PostHogApi(config)
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
            )

        // Call without the required "organization" group
        val result =
            featureFlags.getFeatureFlag(
                key = "org-feature",
                defaultValue = "default",
                distinctId = "user-123",
                groups = null,
            )

        // Debug logging
        if (result != false) {
            println("Logger output: ${logger.logs.joinToString("\n")}")
        }

        assertEquals(false, result)
        assertTrue(logger.containsLog("Can't compute group flag 'org-feature' without group 'organization'"))

        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `group-based flag falls back to API when group type index is unknown`() {
        val logger = TestLogger()
        // Create flag with unknown group type index (99 doesn't exist in groupTypeMapping)
        val localEvalResponse =
            """
            {
                "flags": [
                    {
                        "id": 1,
                        "name": "org-feature",
                        "key": "org-feature",
                        "active": true,
                        "filters": {
                            "aggregation_group_type_index": 99,
                            "groups": [
                                {
                                    "properties": [],
                                    "rollout_percentage": 100
                                }
                            ]
                        },
                        "version": 1
                    }
                ],
                "group_type_mapping": {
                    "0": "account",
                    "2": "organization"
                },
                "cohorts": {}
            }
            """.trimIndent()

        val apiFlagsResponse = createFlagsResponse("org-feature", enabled = true)

        val mockServer =
            createMockHttp(
                jsonResponse(localEvalResponse),
                jsonResponse(apiFlagsResponse),
            )
        val url = mockServer.url("/")

        val config = createTestConfig(logger, url.toString())
        val api = PostHogApi(config)
        val remoteConfig =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
            )

        // Give the poller time to load definitions (async operation)
        Thread.sleep(1000)

        val result =
            remoteConfig.getFeatureFlag(
                key = "org-feature",
                defaultValue = false,
                distinctId = "user-123",
            )

        // Should fall back to API and get true
        assertEquals(true, result)
        assertTrue(logger.containsLog("Unknown group type index 99"))
        assertTrue(logger.containsLog("Local evaluation inconclusive"))

        remoteConfig.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `person-based flag still works with local evaluation`() {
        val logger = TestLogger()
        val localEvalResponse =
            createLocalEvaluationResponse(
                flagKey = "person-feature",
                aggregationGroupTypeIndex = null,
            )

        val mockServer =
            createMockHttp(
                jsonResponse(localEvalResponse),
            )
        val url = mockServer.url("/")

        val config = createTestConfig(logger, url.toString())
        val api = PostHogApi(config)
        val remoteConfig =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
            )

        // Give the poller time to load definitions (async operation)
        Thread.sleep(1000)

        val result =
            remoteConfig.getFeatureFlag(
                key = "person-feature",
                defaultValue = false,
                distinctId = "user-123",
                personProperties = mapOf("email" to "test@example.com"),
            )

        assertEquals(true, result)
        assertTrue(logger.containsLog("Local evaluation successful"))

        remoteConfig.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `distinct_id is available for local evaluation only`() {
        val logger = TestLogger()
        val localEvalResponse =
            """
            {
                "flags": [
                    {
                        "id": 1,
                        "name": "distinct-id-feature",
                        "key": "distinct-id-feature",
                        "active": true,
                        "filters": {
                            "groups": [
                                {
                                    "properties": [
                                        {
                                            "key": "distinct_id",
                                            "operator": "exact",
                                            "value": "user-123",
                                            "type": "person"
                                        }
                                    ],
                                    "rollout_percentage": 100
                                }
                            ]
                        },
                        "version": 1
                    }
                ],
                "group_type_mapping": {},
                "cohorts": {}
            }
            """.trimIndent()

        val mockServer =
            createMockHttp(
                jsonResponse(localEvalResponse),
            )
        val url = mockServer.url("/")

        val config = createTestConfig(logger, url.toString())
        val api = PostHogApi(config)
        val remoteConfig =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
            )

        Thread.sleep(1000)

        val personProperties = mapOf<String, Any?>("region" to "USA")
        val result =
            remoteConfig.getFeatureFlag(
                key = "distinct-id-feature",
                defaultValue = false,
                distinctId = "user-123",
                personProperties = personProperties,
            )

        assertEquals(true, result)
        assertEquals(mapOf("region" to "USA"), personProperties)
        assertEquals(1, mockServer.requestCount, "local evaluation should not fall back to /flags")

        remoteConfig.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `loadFeatureFlagDefinitions overwrites existing definitions on reload`() {
        val logger = TestLogger()

        // Create first response with "flag-v1"
        val firstResponse =
            createLocalEvaluationResponse(
                flagKey = "flag-v1",
                aggregationGroupTypeIndex = null,
            )

        // Create second response with "flag-v2" only (no flag-v1)
        val secondResponse =
            createLocalEvaluationResponse(
                flagKey = "flag-v2",
                aggregationGroupTypeIndex = null,
            )

        val mockServer =
            createMockHttp(
                jsonResponse(firstResponse),
                jsonResponse(secondResponse),
                jsonResponse(secondResponse),
            )
        val url = mockServer.url("/")

        val config = createTestConfig(logger, url.toString())
        val api = PostHogApi(config)
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
            )

        // Wait for initial poller load to complete (loads flag-v1)
        Thread.sleep(1000)

        // Verify first flag is available (loaded by poller)
        val firstResult =
            featureFlags.getFeatureFlag(
                key = "flag-v1",
                defaultValue = false,
                distinctId = "test-user",
            )
        assertEquals(true, firstResult)

        // Load second set of definitions (should overwrite first with flag-v2)
        featureFlags.loadFeatureFlagDefinitions()

        // Verify second flag is now available
        val secondResult =
            featureFlags.getFeatureFlag(
                key = "flag-v2",
                defaultValue = false,
                distinctId = "test-user",
            )
        assertEquals(true, secondResult)

        // Verify first flag is no longer available (was overwritten)
        val firstResultAfterReload =
            featureFlags.getFeatureFlag(
                key = "flag-v1",
                defaultValue = false,
                distinctId = "test-user",
            )
        assertEquals(false, firstResultAfterReload)

        // Verify we made at least 2 API calls (poller's initial load + our manual loads)
        assertTrue(
            mockServer.requestCount >= 2,
            "Expected at least 2 requests, got ${mockServer.requestCount}",
        )
        assertTrue(logger.containsLog("Loading feature flags for local evaluation"))
        assertTrue(logger.containsLog("Loaded 1 feature flags for local evaluation"))

        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `concurrent initial loads only make one API request`() {
        val logger = TestLogger()
        val localEvalResponse =
            createLocalEvaluationResponse(
                flagKey = "test-flag",
                aggregationGroupTypeIndex = null,
            )

        // Provide multiple responses in case duplicate requests happen (we want to verify they don't)
        val mockServer =
            createMockHttp(
                jsonResponse(localEvalResponse),
                jsonResponse(localEvalResponse),
                jsonResponse(localEvalResponse),
            )
        val url = mockServer.url("/")

        val config = createTestConfig(logger, url.toString())
        val api = PostHogApi(config)

        // Create instance and immediately try to use it
        // This simulates the race condition where poller (starts immediately at delay=0)
        // and first flag evaluation both try to load definitions concurrently
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
            )

        // Immediately trigger flag evaluation (which checks definitions and loads if needed)
        // This happens concurrently with poller's initial load
        val result =
            featureFlags.getFeatureFlag(
                key = "test-flag",
                defaultValue = false,
                distinctId = "test-user",
            )

        // Wait a bit to ensure both potential loads have time to complete
        Thread.sleep(1000)

        // Verify the flag works (definitions were loaded successfully)
        assertEquals(true, result)

        // Critical assertion: only 1 API request should have been made
        // The second thread should have waited for the first to complete
        assertEquals(
            1,
            mockServer.requestCount,
            "Expected exactly 1 API request due to concurrent load deduplication, got ${mockServer.requestCount}",
        )

        // Verify we logged the skip message
        assertTrue(
            logger.containsLog("Definitions loaded by another thread, skipping duplicate request") ||
                mockServer.requestCount == 1,
            "Should either log skip message or only make 1 request",
        )

        featureFlags.shutDown()
        mockServer.shutdown()
    }

    @Test
    fun `multiple concurrent loadFeatureFlagDefinitions calls make only one API request`() {
        val logger = TestLogger()
        val localEvalResponse =
            createLocalEvaluationResponse(
                flagKey = "test-flag",
                aggregationGroupTypeIndex = null,
            )

        // Create mock server with DELAYED response (1 second) to ensure all threads enter wait state
        val dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    Thread.sleep(1000) // Simulate slow API
                    return MockResponse()
                        .setResponseCode(200)
                        .setBody(localEvalResponse)
                }
            }
        val mockServer = MockWebServer()
        mockServer.dispatcher = dispatcher
        mockServer.start()
        val url = mockServer.url("/")

        val config = createTestConfig(logger, url.toString())
        val api = PostHogApi(config)
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollerEnabled = false,
            )

        val threadCount = 5
        val startLatch = CountDownLatch(threadCount)
        val threads =
            List(threadCount) {
                Thread {
                    // Wait for all threads to be ready before proceeding. This should reduce
                    // any timing issues where one thread completes before others start - particularly
                    // in CI.
                    startLatch.countDown()
                    startLatch.await()
                    featureFlags.loadFeatureFlagDefinitions()
                }
            }

        threads.forEach { it.start() }

        // Wait for all to complete
        threads.forEach { it.join(5000) }

        // All threads should have completed successfully
        threads.forEach { thread ->
            assertFalse(
                thread.isAlive,
                "Thread should have completed",
            )
        }

        // Critical assertion: only 1 API request despite 5 concurrent calls
        assertEquals(
            1,
            mockServer.requestCount,
            "Expected exactly 1 API request from $threadCount concurrent calls, got ${mockServer.requestCount}",
        )

        // Verify definitions were loaded
        val result = featureFlags.getFeatureFlag("test-flag", false, "test-user")
        assertEquals(true, result)

        // Verify logging shows threads waited
        val skipCount = logger.logs.count { it.contains("Definitions loaded by another thread") }
        assertTrue(
            skipCount >= threadCount - 1,
            "Expected at least ${threadCount - 1} threads to skip duplicate request, but only $skipCount did",
        )

        mockServer.shutdown()
    }

    @Test
    fun `local evaluation sends ETag on subsequent requests`() {
        val logger = TestLogger()
        val localEvalResponse =
            createLocalEvaluationResponse(
                flagKey = "test-flag",
                aggregationGroupTypeIndex = null,
            )

        val mockServer = MockWebServer()
        mockServer.start()

        // First response with ETag
        mockServer.enqueue(jsonResponseWithEtag(localEvalResponse, "\"abc123\""))
        // Second response is 304 Not Modified
        mockServer.enqueue(notModifiedResponse("\"abc123\""))

        val url = mockServer.url("/")

        val config = createTestConfig(logger, url.toString())
        val api = PostHogApi(config)
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollerEnabled = false,
            )

        // First load - should receive ETag
        featureFlags.loadFeatureFlagDefinitions()

        // Second load - should send If-None-Match and receive 304
        featureFlags.loadFeatureFlagDefinitions()

        // Verify we made 2 requests
        assertEquals(2, mockServer.requestCount)

        // Verify the second request included the If-None-Match header
        mockServer.takeRequest() // First request
        val secondRequest = mockServer.takeRequest()
        assertEquals("\"abc123\"", secondRequest.getHeader("If-None-Match"))

        // Verify we logged the 304 response
        assertTrue(logger.containsLog("Feature flags not modified"))

        mockServer.shutdown()
    }

    @Test
    fun `local evaluation uses cached data on 304 Not Modified`() {
        val logger = TestLogger()
        val localEvalResponse =
            createLocalEvaluationResponse(
                flagKey = "test-flag",
                aggregationGroupTypeIndex = null,
            )

        val mockServer = MockWebServer()
        mockServer.start()

        // First response with ETag
        mockServer.enqueue(jsonResponseWithEtag(localEvalResponse, "\"abc123\""))
        // Second response is 304 Not Modified
        mockServer.enqueue(notModifiedResponse("\"abc123\""))

        val url = mockServer.url("/")

        val config = createTestConfig(logger, url.toString())
        val api = PostHogApi(config)
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollerEnabled = false,
            )

        // First load
        featureFlags.loadFeatureFlagDefinitions()

        // Verify flag works after first load
        val result1 =
            featureFlags.getFeatureFlag(
                key = "test-flag",
                defaultValue = false,
                distinctId = "test-user",
            )
        assertEquals(true, result1)

        // Second load - gets 304, should still use cached data
        featureFlags.loadFeatureFlagDefinitions()

        // Verify flag still works after 304
        val result2 =
            featureFlags.getFeatureFlag(
                key = "test-flag",
                defaultValue = false,
                distinctId = "test-user",
            )
        assertEquals(true, result2)

        mockServer.shutdown()
    }

    @Test
    fun `local evaluation clears ETag on error`() {
        val logger = TestLogger()
        val localEvalResponse =
            createLocalEvaluationResponse(
                flagKey = "test-flag",
                aggregationGroupTypeIndex = null,
            )

        val mockServer = MockWebServer()
        mockServer.start()

        // First response with ETag
        mockServer.enqueue(jsonResponseWithEtag(localEvalResponse, "\"abc123\""))
        // Second response is an error
        mockServer.enqueue(errorResponse(500, "Internal Server Error"))
        // Third response - should NOT have If-None-Match since ETag was cleared
        mockServer.enqueue(jsonResponseWithEtag(localEvalResponse, "\"def456\""))

        val url = mockServer.url("/")

        val config = createTestConfig(logger, url.toString())
        val api = PostHogApi(config)
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollerEnabled = false,
            )

        // First load - gets ETag
        featureFlags.loadFeatureFlagDefinitions()

        // Second load - gets error, should clear ETag
        featureFlags.loadFeatureFlagDefinitions()

        // Third load - should NOT send If-None-Match
        featureFlags.loadFeatureFlagDefinitions()

        // Verify we made 3 requests
        assertEquals(3, mockServer.requestCount)

        // Verify the third request did NOT include If-None-Match (ETag was cleared on error)
        mockServer.takeRequest() // First request
        mockServer.takeRequest() // Second request (error)
        val thirdRequest = mockServer.takeRequest()
        assertNull(thirdRequest.getHeader("If-None-Match"))

        mockServer.shutdown()
    }

    @Test
    fun `clear also clears ETag`() {
        val logger = TestLogger()
        val localEvalResponse =
            createLocalEvaluationResponse(
                flagKey = "test-flag",
                aggregationGroupTypeIndex = null,
            )

        val mockServer = MockWebServer()
        mockServer.start()

        // First response with ETag
        mockServer.enqueue(jsonResponseWithEtag(localEvalResponse, "\"abc123\""))
        // Second response after clear - should NOT have If-None-Match
        mockServer.enqueue(jsonResponseWithEtag(localEvalResponse, "\"def456\""))

        val url = mockServer.url("/")

        val config = createTestConfig(logger, url.toString())
        val api = PostHogApi(config)
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollerEnabled = false,
            )

        // First load - gets ETag
        featureFlags.loadFeatureFlagDefinitions()

        // Clear cache and ETag
        featureFlags.clear()

        // Second load - should NOT send If-None-Match
        featureFlags.loadFeatureFlagDefinitions()

        // Verify we made 2 requests
        assertEquals(2, mockServer.requestCount)

        // Verify the second request did NOT include If-None-Match (ETag was cleared)
        mockServer.takeRequest() // First request
        val secondRequest = mockServer.takeRequest()
        assertNull(secondRequest.getHeader("If-None-Match"))

        mockServer.shutdown()
    }

    @Test
    fun `ETag polling reduces bandwidth when flags unchanged`() {
        val logger = TestLogger()
        val localEvalResponse =
            createLocalEvaluationResponse(
                flagKey = "test-flag",
                aggregationGroupTypeIndex = null,
            )

        val mockServer = MockWebServer()
        mockServer.start()

        // First response with ETag and full body
        mockServer.enqueue(jsonResponseWithEtag(localEvalResponse, "\"etag-v1\""))
        // Subsequent responses are 304 Not Modified (no body, minimal bandwidth)
        mockServer.enqueue(notModifiedResponse("\"etag-v1\""))
        mockServer.enqueue(notModifiedResponse("\"etag-v1\""))
        mockServer.enqueue(notModifiedResponse("\"etag-v1\""))

        val url = mockServer.url("/")

        val config = createTestConfig(logger, url.toString())
        val api = PostHogApi(config)
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollerEnabled = false,
            )

        // Load multiple times
        repeat(4) {
            featureFlags.loadFeatureFlagDefinitions()
        }

        // Verify we made 4 requests
        assertEquals(4, mockServer.requestCount)

        // First request has no If-None-Match
        val firstRequest = mockServer.takeRequest()
        assertNull(firstRequest.getHeader("If-None-Match"))

        // Subsequent requests have If-None-Match with ETag
        repeat(3) {
            val request = mockServer.takeRequest()
            assertEquals("\"etag-v1\"", request.getHeader("If-None-Match"))
        }

        // Verify flag still works
        val result =
            featureFlags.getFeatureFlag(
                key = "test-flag",
                defaultValue = false,
                distinctId = "test-user",
            )
        assertEquals(true, result)

        // Verify we logged the not modified messages (both API and feature flags layer log)
        val apiNotModifiedCount = logger.countLogs("Feature flags not modified (304)")
        val featureFlagsNotModifiedCount = logger.countLogs("using cached definitions")
        assertEquals(3, apiNotModifiedCount, "Expected 3 API-level 'not modified' log messages")
        assertEquals(3, featureFlagsNotModifiedCount, "Expected 3 feature flags 'cached definitions' log messages")

        mockServer.shutdown()
    }

    @Test
    fun `local evaluation updates ETag when flags change`() {
        val logger = TestLogger()
        val firstResponse =
            createLocalEvaluationResponse(
                flagKey = "test-flag-v1",
                aggregationGroupTypeIndex = null,
            )
        val secondResponse =
            createLocalEvaluationResponse(
                flagKey = "test-flag-v2",
                aggregationGroupTypeIndex = null,
            )

        val mockServer = MockWebServer()
        mockServer.start()

        // First response with ETag v1
        mockServer.enqueue(jsonResponseWithEtag(firstResponse, "\"etag-v1\""))
        // Second response: flags changed, new ETag v2
        mockServer.enqueue(jsonResponseWithEtag(secondResponse, "\"etag-v2\""))
        // Third response: 304 with the new ETag
        mockServer.enqueue(notModifiedResponse("\"etag-v2\""))

        val url = mockServer.url("/")

        val config = createTestConfig(logger, url.toString())
        val api = PostHogApi(config)
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollerEnabled = false,
            )

        // First load - gets etag-v1
        featureFlags.loadFeatureFlagDefinitions()

        // Verify first request has no If-None-Match
        val firstRequest = mockServer.takeRequest()
        assertNull(firstRequest.getHeader("If-None-Match"))

        // Second load - gets new data and etag-v2
        featureFlags.loadFeatureFlagDefinitions()

        // Verify second request sent old ETag
        val secondRequest = mockServer.takeRequest()
        assertEquals("\"etag-v1\"", secondRequest.getHeader("If-None-Match"))

        // Third load - should send new ETag and get 304
        featureFlags.loadFeatureFlagDefinitions()

        // Verify third request sent new ETag
        val thirdRequest = mockServer.takeRequest()
        assertEquals("\"etag-v2\"", thirdRequest.getHeader("If-None-Match"))

        // Verify the new flag is available
        val result = featureFlags.getFeatureFlag("test-flag-v2", false, "test-user")
        assertEquals(true, result)

        mockServer.shutdown()
    }

    @Test
    fun `loadFeatureFlagDefinitions uses cached definitions when provider skips fetch`() {
        val logger = TestLogger()
        val mockServer = MockWebServer()
        mockServer.start()
        val config = createTestConfig(logger, mockServer.url("/").toString())
        val api = PostHogApi(config)
        val provider =
            TestFlagDefinitionCacheProvider(
                cacheData = createFlagDefinitionCacheData(config, "cached-flag"),
                shouldFetch = false,
            )
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollerEnabled = false,
                flagDefinitionCacheProvider = provider,
            )

        featureFlags.loadFeatureFlagDefinitions()

        assertEquals(0, mockServer.requestCount)
        assertEquals(1, provider.shouldFetchCalls)
        assertEquals(1, provider.getCalls)
        assertEquals(0, provider.onReceivedCalls)
        assertEquals(true, featureFlags.getFeatureFlag("cached-flag", false, "test-user"))
        assertTrue(logger.containsLog("Loaded 1 feature flags from flag definition cache"))

        mockServer.shutdown()
    }

    @Test
    fun `loadFeatureFlagDefinitions awaits async cached definitions when provider skips fetch`() {
        val logger = TestLogger()
        val mockServer = MockWebServer()
        mockServer.start()
        val config = createTestConfig(logger, mockServer.url("/").toString())
        val api = PostHogApi(config)
        val delegate =
            TestFlagDefinitionCacheProvider(
                cacheData = createFlagDefinitionCacheData(config, "async-cached-flag"),
                shouldFetch = false,
                delayOnGetMs = 50,
            )
        val provider = AsyncTestFlagDefinitionCacheProvider(delegate)
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollerEnabled = false,
                flagDefinitionCacheProvider = provider,
            )

        featureFlags.loadFeatureFlagDefinitions()

        assertEquals(0, mockServer.requestCount)
        assertEquals(1, delegate.shouldFetchCalls)
        assertEquals(1, delegate.getCalls)
        assertEquals(true, featureFlags.getFeatureFlag("async-cached-flag", false, "test-user"))

        mockServer.shutdown()
    }

    @Test
    fun `loadFeatureFlagDefinitions awaits async store when provider should fetch`() {
        val logger = TestLogger()
        val mockServer =
            createMockHttp(
                jsonResponse(createLocalEvaluationResponse("async-api-flag")),
            )
        val config = createTestConfig(logger, mockServer.url("/").toString())
        val api = PostHogApi(config)
        val delegate = TestFlagDefinitionCacheProvider(shouldFetch = true)
        val provider = AsyncTestFlagDefinitionCacheProvider(delegate)
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollerEnabled = false,
                flagDefinitionCacheProvider = provider,
            )

        featureFlags.loadFeatureFlagDefinitions()

        assertEquals(1, mockServer.requestCount)
        assertEquals(1, delegate.shouldFetchCalls)
        assertEquals(0, delegate.getCalls)
        assertEquals(1, delegate.onReceivedCalls)
        assertTrue(serializeFlagDefinitionCacheData(config, delegate.lastReceivedData).contains("\"key\":\"async-api-flag\""))
        assertEquals(true, featureFlags.getFeatureFlag("async-api-flag", false, "test-user"))

        mockServer.shutdown()
    }

    @Test
    fun `loadFeatureFlagDefinitions fetches and stores definitions when provider should fetch`() {
        val logger = TestLogger()
        val mockServer =
            createMockHttp(
                jsonResponse(createLocalEvaluationResponse("api-flag")),
            )
        val config = createTestConfig(logger, mockServer.url("/").toString())
        val api = PostHogApi(config)
        val provider = TestFlagDefinitionCacheProvider(shouldFetch = true)
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollerEnabled = false,
                flagDefinitionCacheProvider = provider,
            )

        featureFlags.loadFeatureFlagDefinitions()

        assertEquals(1, mockServer.requestCount)
        assertEquals(1, provider.shouldFetchCalls)
        assertEquals(0, provider.getCalls)
        assertEquals(1, provider.onReceivedCalls)
        assertTrue(serializeFlagDefinitionCacheData(config, provider.lastReceivedData).contains("\"key\":\"api-flag\""))
        assertEquals(true, featureFlags.getFeatureFlag("api-flag", false, "test-user"))

        mockServer.shutdown()
    }

    @Test
    fun `loadFeatureFlagDefinitions API fallback cases keep local evaluation available`() {
        flagDefinitionCacheApiFallbackCases.forEach { testCase ->
            val logger = TestLogger()
            val mockServer =
                createMockHttp(
                    jsonResponse(createLocalEvaluationResponse(testCase.flagKey)),
                )
            val config = createTestConfig(logger, mockServer.url("/").toString())
            val api = PostHogApi(config)
            val provider = TestFlagDefinitionCacheProvider().apply(testCase.configureProvider)
            val featureFlags =
                PostHogFeatureFlags(
                    config,
                    api,
                    60000,
                    100,
                    localEvaluation = true,
                    personalApiKey = "test-personal-key",
                    pollerEnabled = false,
                    flagDefinitionCacheProvider = provider,
                )

            featureFlags.loadFeatureFlagDefinitions()

            assertEquals(1, mockServer.requestCount, "${testCase.name}: request count")
            assertEquals(1, provider.shouldFetchCalls, "${testCase.name}: shouldFetch calls")
            assertEquals(testCase.expectedGetCalls, provider.getCalls, "${testCase.name}: get calls")
            assertEquals(testCase.expectedOnReceivedCalls, provider.onReceivedCalls, "${testCase.name}: onReceived calls")
            assertEquals(true, featureFlags.getFeatureFlag(testCase.flagKey, false, "test-user"), "${testCase.name}: flag result")
            assertTrue(logger.containsLog(testCase.expectedLog), "${testCase.name}: expected log")

            mockServer.shutdown()
        }
    }

    @Test
    fun `loadFeatureFlagDefinitions keeps existing definitions when follower cache is unavailable`() {
        flagDefinitionCacheKeepExistingCases.forEach { testCase ->
            val logger = TestLogger()
            val mockServer =
                createMockHttp(
                    jsonResponse(createLocalEvaluationResponse(testCase.flagKey)),
                )
            val config = createTestConfig(logger, mockServer.url("/").toString())
            val api = PostHogApi(config)
            val provider = TestFlagDefinitionCacheProvider(shouldFetch = true)
            val featureFlags =
                PostHogFeatureFlags(
                    config,
                    api,
                    60000,
                    100,
                    localEvaluation = true,
                    personalApiKey = "test-personal-key",
                    pollerEnabled = false,
                    flagDefinitionCacheProvider = provider,
                )

            featureFlags.loadFeatureFlagDefinitions()
            provider.apply(testCase.configureProviderAfterWarmLoad)
            featureFlags.loadFeatureFlagDefinitions()

            assertEquals(1, mockServer.requestCount, "${testCase.name}: request count")
            assertEquals(2, provider.shouldFetchCalls, "${testCase.name}: shouldFetch calls")
            assertEquals(1, provider.getCalls, "${testCase.name}: get calls")
            assertEquals(1, provider.onReceivedCalls, "${testCase.name}: onReceived calls")
            assertEquals(true, featureFlags.getFeatureFlag(testCase.flagKey, false, "test-user"), "${testCase.name}: flag result")
            assertTrue(logger.containsLog("keeping existing definitions"), "${testCase.name}: expected stale-definition log")
            testCase.expectedAdditionalLog?.let {
                assertTrue(logger.containsLog(it), "${testCase.name}: expected additional log")
            }

            mockServer.shutdown()
        }
    }

    @Test
    fun `loadFeatureFlagDefinitions does not store definitions on 304 Not Modified`() {
        val logger = TestLogger()
        val localEvalResponse = createLocalEvaluationResponse("etag-cache-flag")
        val mockServer = MockWebServer()
        mockServer.start()
        mockServer.enqueue(jsonResponseWithEtag(localEvalResponse, "\"etag-cache\""))
        mockServer.enqueue(notModifiedResponse("\"etag-cache\""))

        val config = createTestConfig(logger, mockServer.url("/").toString())
        val api = PostHogApi(config)
        val provider = TestFlagDefinitionCacheProvider(shouldFetch = true)
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollerEnabled = false,
                flagDefinitionCacheProvider = provider,
            )

        featureFlags.loadFeatureFlagDefinitions()
        featureFlags.loadFeatureFlagDefinitions()

        assertEquals(2, mockServer.requestCount)
        assertEquals(1, provider.onReceivedCalls)

        mockServer.shutdown()
    }

    @Test
    fun `loadFeatureFlagDefinitions picks up updated cached definitions on subsequent polls`() {
        val logger = TestLogger()
        val mockServer = MockWebServer()
        mockServer.start()
        val config = createTestConfig(logger, mockServer.url("/").toString())
        val api = PostHogApi(config)
        val provider =
            TestFlagDefinitionCacheProvider(
                cacheData = createFlagDefinitionCacheData(config, "cached-flag-v1"),
                shouldFetch = false,
            )
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollerEnabled = false,
                flagDefinitionCacheProvider = provider,
            )

        featureFlags.loadFeatureFlagDefinitions()
        provider.cacheData = createFlagDefinitionCacheData(config, "cached-flag-v2")
        featureFlags.loadFeatureFlagDefinitions()

        assertEquals(0, mockServer.requestCount)
        assertEquals(2, provider.shouldFetchCalls)
        assertEquals(2, provider.getCalls)
        assertEquals(true, featureFlags.getFeatureFlag("cached-flag-v2", false, "test-user"))

        mockServer.shutdown()
    }

    @Test
    fun `cached group flag uses group type mapping from provider`() {
        val logger = TestLogger()
        val mockServer = MockWebServer()
        mockServer.start()
        val config = createTestConfig(logger, mockServer.url("/").toString())
        val api = PostHogApi(config)
        val provider =
            TestFlagDefinitionCacheProvider(
                cacheData = createFlagDefinitionCacheData(config, "cached-group-flag", aggregationGroupTypeIndex = 2),
                shouldFetch = false,
            )
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollerEnabled = false,
                flagDefinitionCacheProvider = provider,
            )

        featureFlags.loadFeatureFlagDefinitions()
        val result =
            featureFlags.getFeatureFlag(
                key = "cached-group-flag",
                defaultValue = false,
                distinctId = "user-123",
                groups = mapOf("organization" to "org-456"),
                groupProperties = mapOf("org-456" to mapOf("plan" to "enterprise")),
            )

        assertEquals(true, result)
        assertEquals(0, mockServer.requestCount)

        mockServer.shutdown()
    }

    @Test
    fun `cached inactive flag evaluates false without remote fallback`() {
        val logger = TestLogger()
        val mockServer = MockWebServer()
        mockServer.start()
        val config = createTestConfig(logger, mockServer.url("/").toString())
        val api = PostHogApi(config)
        val inactiveFlagJson = createLocalEvaluationResponse("inactive-cache-flag").replace("\"active\": true", "\"active\": false")
        val provider =
            TestFlagDefinitionCacheProvider(
                cacheData = createFlagDefinitionCacheDataFromJson(config, inactiveFlagJson),
                shouldFetch = false,
            )
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollerEnabled = false,
                flagDefinitionCacheProvider = provider,
            )

        featureFlags.loadFeatureFlagDefinitions()
        val result = featureFlags.getFeatureFlag("inactive-cache-flag", true, "test-user")

        assertEquals(false, result)
        assertEquals(0, mockServer.requestCount)

        mockServer.shutdown()
    }

    @Test
    fun `cached cohort flag uses cohorts from provider`() {
        val logger = TestLogger()
        val mockServer = MockWebServer()
        mockServer.start()
        val config = createTestConfig(logger, mockServer.url("/").toString())
        val api = PostHogApi(config)
        val provider =
            TestFlagDefinitionCacheProvider(
                cacheData = createFlagDefinitionCacheDataFromJson(config, createCohortLocalEvaluationResponse()),
                shouldFetch = false,
            )
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollerEnabled = false,
                flagDefinitionCacheProvider = provider,
            )

        featureFlags.loadFeatureFlagDefinitions()
        val result =
            featureFlags.getFeatureFlag(
                key = "cohort-member",
                defaultValue = false,
                distinctId = "user-123",
                personProperties = mapOf("email" to "example@example.com"),
            )

        assertEquals(true, result)
        assertEquals(0, mockServer.requestCount)

        mockServer.shutdown()
    }

    @Test
    fun `stored flag definition cache JSON preserves complex endpoint shape`() {
        val logger = TestLogger()
        val mockServer = createMockHttp(jsonResponse(createCohortLocalEvaluationResponse()))
        val config = createTestConfig(logger, mockServer.url("/").toString())
        val api = PostHogApi(config)
        val provider = TestFlagDefinitionCacheProvider(shouldFetch = true)
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollerEnabled = false,
                flagDefinitionCacheProvider = provider,
            )

        featureFlags.loadFeatureFlagDefinitions()

        val cachedData = provider.lastReceivedData
        val cachedJson = serializeFlagDefinitionCacheData(config, cachedData)
        assertTrue(cachedJson.contains("\"group_type_mapping\":"))
        assertTrue(cachedJson.contains("\"operator\":\"not_regex\""))
        assertTrue(cachedJson.contains("\"type\":\"person\""))
        assertTrue(cachedJson.contains("\"type\":\"cohort\""))
        assertFalse(cachedJson.contains("NOT_REGEX"))
        assertFalse(cachedJson.contains("\"values\":{\"values\""))

        val cacheProvider =
            TestFlagDefinitionCacheProvider(
                cacheData = roundTripFlagDefinitionCacheData(config, cachedData),
                shouldFetch = false,
            )
        val cacheFeatureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollerEnabled = false,
                flagDefinitionCacheProvider = cacheProvider,
            )

        cacheFeatureFlags.loadFeatureFlagDefinitions()
        val cachedResult =
            cacheFeatureFlags.getFeatureFlag(
                key = "cohort-member",
                defaultValue = false,
                distinctId = "user-123",
                personProperties = mapOf("email" to "example@example.com"),
            )

        assertEquals(true, cachedResult)
        assertEquals(1, mockServer.requestCount)

        mockServer.shutdown()
    }

    @Test
    fun `flag definition cache data uses endpoint JSON with snake case group mapping`() {
        val logger = TestLogger()
        val mockServer = MockWebServer()
        mockServer.start()
        val config = createTestConfig(logger, mockServer.url("/").toString())
        val data = createFlagDefinitionCacheData(config, "roundtrip-cache-flag")
        val json = serializeFlagDefinitionCacheData(config, data)
        val api = PostHogApi(config)
        val provider =
            TestFlagDefinitionCacheProvider(
                cacheData = data,
                shouldFetch = false,
            )
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollerEnabled = false,
                flagDefinitionCacheProvider = provider,
            )

        featureFlags.loadFeatureFlagDefinitions()

        assertTrue(json.contains("group_type_mapping"))
        assertFalse(json.contains("groupTypeMapping"))
        assertEquals(true, featureFlags.getFeatureFlag("roundtrip-cache-flag", false, "test-user"))
        assertEquals(0, mockServer.requestCount)

        mockServer.shutdown()
    }

    @Test
    fun `concurrent cache loads share one provider read`() {
        val logger = TestLogger()
        val mockServer = MockWebServer()
        mockServer.start()
        val config = createTestConfig(logger, mockServer.url("/").toString())
        val api = PostHogApi(config)
        val provider =
            TestFlagDefinitionCacheProvider(
                cacheData = createFlagDefinitionCacheData(config, "concurrent-cache-flag"),
                shouldFetch = false,
                delayOnGetMs = 300,
            )
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollerEnabled = false,
                flagDefinitionCacheProvider = provider,
            )
        val startLatch = CountDownLatch(1)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val threads =
            (1..5).map {
                Thread {
                    try {
                        startLatch.await()
                        featureFlags.loadFeatureFlagDefinitions()
                    } catch (e: Throwable) {
                        errors.add(e)
                    }
                }
            }

        threads.forEach { it.start() }
        startLatch.countDown()
        threads.forEach { it.join() }

        assertTrue(errors.isEmpty(), "Unexpected errors: $errors")
        assertEquals(0, mockServer.requestCount)
        assertEquals(1, provider.shouldFetchCalls)
        assertEquals(1, provider.getCalls)
        assertEquals(true, featureFlags.getFeatureFlag("concurrent-cache-flag", false, "test-user"))

        mockServer.shutdown()
    }

    @Test
    fun `shutdown calls provider after definitions loaded from cache`() {
        val logger = TestLogger()
        val mockServer = MockWebServer()
        mockServer.start()
        val config = createTestConfig(logger, mockServer.url("/").toString())
        val api = PostHogApi(config)
        val provider =
            TestFlagDefinitionCacheProvider(
                cacheData = createFlagDefinitionCacheData(config, "shutdown-cache-flag"),
                shouldFetch = false,
            )
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollerEnabled = false,
                flagDefinitionCacheProvider = provider,
            )

        featureFlags.loadFeatureFlagDefinitions()
        featureFlags.shutDown()

        assertEquals(0, mockServer.requestCount)
        assertEquals(1, provider.shutdownCalls)

        mockServer.shutdown()
    }

    @Test
    fun `provider write and shutdown errors are logged without clearing definitions`() {
        val logger = TestLogger()
        val mockServer =
            createMockHttp(
                jsonResponse(createLocalEvaluationResponse("resilient-flag")),
            )
        val config = createTestConfig(logger, mockServer.url("/").toString())
        val api = PostHogApi(config)
        val provider =
            TestFlagDefinitionCacheProvider(
                shouldFetch = true,
                throwOnReceived = true,
                throwOnShutdown = true,
            )
        val featureFlags =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollerEnabled = false,
                flagDefinitionCacheProvider = provider,
            )

        featureFlags.loadFeatureFlagDefinitions()
        featureFlags.shutDown()

        assertEquals(true, featureFlags.getFeatureFlag("resilient-flag", false, "test-user"))
        assertEquals(1, provider.onReceivedCalls)
        assertEquals(1, provider.shutdownCalls)
        assertTrue(logger.containsLog("Error storing feature flag definitions in cache provider"))
        assertTrue(logger.containsLog("Error shutting down flag definition cache provider"))

        mockServer.shutdown()
    }

    private fun createFlagDefinitionCacheData(
        config: com.posthog.PostHogConfig,
        flagKey: String,
        aggregationGroupTypeIndex: Int? = null,
    ): Map<String, Any?> =
        createFlagDefinitionCacheDataFromJson(
            config,
            createLocalEvaluationResponse(flagKey, aggregationGroupTypeIndex = aggregationGroupTypeIndex),
        )

    private fun createFlagDefinitionCacheDataFromJson(
        config: com.posthog.PostHogConfig,
        json: String,
    ): Map<String, Any?> = config.serializer.deserialize(StringReader(json))

    private fun serializeFlagDefinitionCacheData(
        config: com.posthog.PostHogConfig,
        data: Map<String, Any?>?,
    ): String {
        val writer = StringWriter()
        config.serializer.serialize(data ?: emptyMap<String, Any?>(), writer)
        return writer.toString()
    }

    private fun roundTripFlagDefinitionCacheData(
        config: com.posthog.PostHogConfig,
        data: Map<String, Any?>?,
    ): Map<String, Any?> = config.serializer.deserialize(StringReader(serializeFlagDefinitionCacheData(config, data)))

    private fun createCohortLocalEvaluationResponse(): String =
        """
        {
            "flags": [
                {
                    "id": 26,
                    "name": "Cohort Member",
                    "key": "cohort-member",
                    "active": true,
                    "filters": {
                        "groups": [
                            {
                                "properties": [
                                    {
                                        "key": "id",
                                        "value": 2,
                                        "operator": "in",
                                        "type": "cohort",
                                        "negation": false
                                    }
                                ],
                                "rollout_percentage": 100
                            }
                        ]
                    },
                    "version": 2
                }
            ],
            "group_type_mapping": {},
            "cohorts": {
                "2": {
                    "type": "AND",
                    "values": [
                        {
                            "type": "AND",
                            "values": [
                                {
                                    "key": "email",
                                    "operator": "not_regex",
                                    "type": "person",
                                    "value": "@hedgebox.net$"
                                }
                            ]
                        },
                        {
                            "type": "AND",
                            "values": [
                                {
                                    "key": "id",
                                    "type": "cohort",
                                    "negation": true,
                                    "value": 3
                                },
                                {
                                    "key": "email",
                                    "operator": "is_set",
                                    "type": "person",
                                    "negation": false,
                                    "value": "is_set"
                                }
                            ]
                        }
                    ]
                },
                "3": {
                    "type": "OR",
                    "values": [
                        {
                            "type": "AND",
                            "values": [
                                {
                                    "key": "email",
                                    "operator": "regex",
                                    "type": "person",
                                    "negation": false,
                                    "value": "@gmail.com"
                                }
                            ]
                        }
                    ]
                }
            }
        }
        """.trimIndent()

    private data class FlagDefinitionCacheApiFallbackCase(
        val name: String,
        val flagKey: String,
        val configureProvider: TestFlagDefinitionCacheProvider.() -> Unit,
        val expectedGetCalls: Int,
        val expectedOnReceivedCalls: Int,
        val expectedLog: String,
    )

    private data class FlagDefinitionCacheKeepExistingCase(
        val name: String,
        val flagKey: String,
        val configureProviderAfterWarmLoad: TestFlagDefinitionCacheProvider.() -> Unit,
        val expectedAdditionalLog: String? = null,
    )

    private val flagDefinitionCacheApiFallbackCases =
        listOf(
            FlagDefinitionCacheApiFallbackCase(
                name = "cold cache miss",
                flagKey = "fallback-flag",
                configureProvider = { shouldFetch = false },
                expectedGetCalls = 1,
                expectedOnReceivedCalls = 0,
                expectedLog = "falling back to API",
            ),
            FlagDefinitionCacheApiFallbackCase(
                name = "shouldFetch throws",
                flagKey = "api-flag",
                configureProvider = { throwOnShouldFetch = true },
                expectedGetCalls = 0,
                expectedOnReceivedCalls = 1,
                expectedLog = "shouldFetchFlagDefinitions",
            ),
            FlagDefinitionCacheApiFallbackCase(
                name = "cache read throws on cold load",
                flagKey = "get-error-fallback-flag",
                configureProvider = {
                    shouldFetch = false
                    throwOnGet = true
                },
                expectedGetCalls = 1,
                expectedOnReceivedCalls = 0,
                expectedLog = "Error loading feature flag definitions from cache provider",
            ),
        )

    private val flagDefinitionCacheKeepExistingCases =
        listOf(
            FlagDefinitionCacheKeepExistingCase(
                name = "cache miss after warm load",
                flagKey = "existing-flag",
                configureProviderAfterWarmLoad = {
                    shouldFetch = false
                    cacheData = null
                },
            ),
            FlagDefinitionCacheKeepExistingCase(
                name = "cache read throws after warm load",
                flagKey = "existing-after-get-error-flag",
                configureProviderAfterWarmLoad = {
                    shouldFetch = false
                    throwOnGet = true
                },
                expectedAdditionalLog = "Error loading feature flag definitions from cache provider",
            ),
        )

    private class AsyncTestFlagDefinitionCacheProvider(
        private val delegate: TestFlagDefinitionCacheProvider,
    ) : PostHogFlagDefinitionCacheProvider {
        override fun getFlagDefinitions(): CompletionStage<Map<String, Any?>?> =
            CompletableFuture.supplyAsync<Map<String, Any?>?> {
                delegate.getFlagDefinitionsBlocking()
            }

        override fun shouldFetchFlagDefinitions(): CompletionStage<Boolean> =
            CompletableFuture.supplyAsync<Boolean> {
                delegate.shouldFetchFlagDefinitionsBlocking()
            }

        override fun onFlagDefinitionsReceived(data: Map<String, Any?>): CompletionStage<Void?> =
            CompletableFuture.supplyAsync<Void?> {
                delegate.onFlagDefinitionsReceivedBlocking(data)
                null
            }

        override fun shutdown(): CompletionStage<Void?> =
            CompletableFuture.supplyAsync<Void?> {
                delegate.shutdownBlocking()
                null
            }
    }

    private class TestFlagDefinitionCacheProvider(
        var cacheData: Map<String, Any?>? = null,
        var shouldFetch: Boolean = true,
        var throwOnShouldFetch: Boolean = false,
        var throwOnGet: Boolean = false,
        var throwOnReceived: Boolean = false,
        var throwOnShutdown: Boolean = false,
        var delayOnGetMs: Long = 0,
    ) : PostHogBlockingFlagDefinitionCacheProvider() {
        var shouldFetchCalls = 0
        var getCalls = 0
        var onReceivedCalls = 0
        var shutdownCalls = 0
        var lastReceivedData: Map<String, Any?>? = null

        override fun getFlagDefinitionsBlocking(): Map<String, Any?>? {
            getCalls += 1
            if (delayOnGetMs > 0) {
                Thread.sleep(delayOnGetMs)
            }
            if (throwOnGet) {
                throw IllegalStateException("get failed")
            }
            return cacheData
        }

        override fun shouldFetchFlagDefinitionsBlocking(): Boolean {
            shouldFetchCalls += 1
            if (throwOnShouldFetch) {
                throw IllegalStateException("should fetch failed")
            }
            return shouldFetch
        }

        override fun onFlagDefinitionsReceivedBlocking(data: Map<String, Any?>) {
            onReceivedCalls += 1
            lastReceivedData = data
            if (throwOnReceived) {
                throw IllegalStateException("write failed")
            }
        }

        override fun shutdownBlocking() {
            shutdownCalls += 1
            if (throwOnShutdown) {
                throw IllegalStateException("shutdown failed")
            }
        }
    }
}
