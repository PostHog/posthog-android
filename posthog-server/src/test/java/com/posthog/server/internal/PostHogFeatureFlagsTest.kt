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
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val localEvalResponse = createLocalEvaluationResponse(
            flagKey = "test-flag",
            aggregationGroupTypeIndex = null,
        )

        val mockServer = createMockHttp(
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
            "Expected at least 1 request, got ${mockServer.requestCount}"
        )
        assertTrue(logger.containsLog("Loading feature flags for local evaluation"))
        assertTrue(logger.containsLog("Loaded 1 feature flags for local evaluation") || logger.logs.any {
            it.contains(
                "Loaded"
            )
        })

        remoteConfig.shutDown()
        mockServer.shutdown()
    }
}
