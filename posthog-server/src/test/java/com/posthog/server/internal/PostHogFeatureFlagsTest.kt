package com.posthog.server.internal

import com.posthog.internal.PostHogApi
import com.posthog.server.TestLogger
import com.posthog.server.createEmptyFlagsResponse
import com.posthog.server.createFlagsResponse
import com.posthog.server.createLocalEvaluationResponse
import com.posthog.server.createMockHttp
import com.posthog.server.createTestConfig
import com.posthog.server.errorResponse
import com.posthog.server.jsonResponse
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
        assertTrue(logger.containsLog("Loading remote feature flags failed"))
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
        val remoteConfig =
            PostHogFeatureFlags(
                config,
                api,
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                pollIntervalSeconds = 30,
            )

        // Wait for poller to load
        Thread.sleep(2000)

        // Check that we made the API call
        assertTrue(
            mockServer.requestCount >= 1,
            "Expected at least 1 request, got ${mockServer.requestCount}",
        )
        assertTrue(logger.containsLog("Loading feature flags for local evaluation"))
        assertTrue(
            logger.containsLog("Loaded 1 feature flags for local evaluation") ||
                logger.logs.any {
                    it.contains(
                        "Loaded",
                    )
                },
        )

        remoteConfig.shutDown()
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
            )

        // Shut down poller to control loading manually
        featureFlags.shutDown()

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
}
