package com.posthog.server.internal

import com.posthog.internal.PostHogApi
import com.posthog.server.PostHogBlockingFlagDefinitionCacheProvider
import com.posthog.server.PostHogFlagDefinitionCacheProvider
import com.posthog.server.TestLogger
import com.posthog.server.createEmptyFlagsResponse
import com.posthog.server.createFlagsResponse
import com.posthog.server.createLocalEvaluationResponse
import com.posthog.server.createMockHttp
import com.posthog.server.createTestConfig
import com.posthog.server.errorResponse
import com.posthog.server.jsonResponse
import com.posthog.server.jsonResponseWithEtag
import com.posthog.server.notModifiedResponse
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
    fun `local evaluation defaults has_experiment to false when definitions omit it`() {
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

        assertEquals(false, result.flags["plain-flag"]?.metadata?.hasExperiment)

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
