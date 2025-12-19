package com.posthog.server.internal

import com.posthog.internal.PostHogApi
import com.posthog.server.createEmptyFlagsResponse
import com.posthog.server.createFlagsResponse
import com.posthog.server.createFlagsResponseWithErrors
import com.posthog.server.createFlagsResponseWithQuotaLimited
import com.posthog.server.createMockHttp
import com.posthog.server.createTestConfig
import com.posthog.server.errorResponse
import com.posthog.server.jsonResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class FeatureFlagErrorTrackingTest {
    @Test
    fun `getFeatureFlagError returns errors_while_computing_flags when errorsWhileComputingFlags is true`() {
        val flagsResponse = createFlagsResponseWithErrors("test-flag", enabled = true)
        val mockServer = createMockHttp(jsonResponse(flagsResponse))
        val url = mockServer.url("/")

        val config = createTestConfig(host = url.toString())
        val api = PostHogApi(config)
        val remoteConfig = PostHogFeatureFlags(config, api, 60000, 100)

        // Trigger flag evaluation to populate cache
        remoteConfig.getFeatureFlag(
            key = "test-flag",
            defaultValue = "default",
            distinctId = "test-user",
        )

        val error =
            remoteConfig.getFeatureFlagError(
                key = "test-flag",
                distinctId = "test-user",
            )

        assertEquals(FeatureFlagError.ERRORS_WHILE_COMPUTING, error)
        mockServer.shutdown()
    }

    @Test
    fun `getFeatureFlagError returns flag_missing when flag not in response`() {
        val flagsResponse = createEmptyFlagsResponse()
        val mockServer = createMockHttp(jsonResponse(flagsResponse))
        val url = mockServer.url("/")

        val config = createTestConfig(host = url.toString())
        val api = PostHogApi(config)
        val remoteConfig = PostHogFeatureFlags(config, api, 60000, 100)

        // Trigger flag evaluation to populate cache
        remoteConfig.getFeatureFlag(
            key = "missing-flag",
            defaultValue = "default",
            distinctId = "test-user",
        )

        val error =
            remoteConfig.getFeatureFlagError(
                key = "missing-flag",
                distinctId = "test-user",
            )

        assertEquals(FeatureFlagError.FLAG_MISSING, error)
        mockServer.shutdown()
    }

    @Test
    fun `getFeatureFlagError returns quota_limited when quotaLimited contains feature_flags`() {
        val flagsResponse = createFlagsResponseWithQuotaLimited()
        val mockServer = createMockHttp(jsonResponse(flagsResponse))
        val url = mockServer.url("/")

        val config = createTestConfig(host = url.toString())
        val api = PostHogApi(config)
        val remoteConfig = PostHogFeatureFlags(config, api, 60000, 100)

        // Trigger flag evaluation to populate cache
        remoteConfig.getFeatureFlag(
            key = "test-flag",
            defaultValue = "default",
            distinctId = "test-user",
        )

        val error =
            remoteConfig.getFeatureFlagError(
                key = "test-flag",
                distinctId = "test-user",
            )

        assertEquals("${FeatureFlagError.QUOTA_LIMITED},${FeatureFlagError.FLAG_MISSING}", error)
        mockServer.shutdown()
    }

    @Test
    fun `getFeatureFlagError returns api_error_500 when server returns 500`() {
        val mockServer = createMockHttp(errorResponse(500, "Internal Server Error"))
        val url = mockServer.url("/")

        val config = createTestConfig(host = url.toString())
        val api = PostHogApi(config)
        val remoteConfig = PostHogFeatureFlags(config, api, 60000, 100)

        // Trigger flag evaluation to populate cache with error
        remoteConfig.getFeatureFlag(
            key = "test-flag",
            defaultValue = "default",
            distinctId = "test-user",
        )

        val error =
            remoteConfig.getFeatureFlagError(
                key = "test-flag",
                distinctId = "test-user",
            )

        assertEquals(FeatureFlagError.apiError(500), error)
        mockServer.shutdown()
    }

    @Test
    fun `getFeatureFlagError returns api_error_403 when server returns 403`() {
        val mockServer = createMockHttp(errorResponse(403, "Forbidden"))
        val url = mockServer.url("/")

        val config = createTestConfig(host = url.toString())
        val api = PostHogApi(config)
        val remoteConfig = PostHogFeatureFlags(config, api, 60000, 100)

        // Trigger flag evaluation to populate cache with error
        remoteConfig.getFeatureFlag(
            key = "test-flag",
            defaultValue = "default",
            distinctId = "test-user",
        )

        val error =
            remoteConfig.getFeatureFlagError(
                key = "test-flag",
                distinctId = "test-user",
            )

        assertEquals(FeatureFlagError.apiError(403), error)
        mockServer.shutdown()
    }

    @Test
    fun `getFeatureFlagError returns connection_error when DNS lookup fails`() {
        // Use an invalid hostname to trigger UnknownHostException
        val config = createTestConfig(host = "http://invalid.invalid.invalid")
        val api = PostHogApi(config)
        val remoteConfig = PostHogFeatureFlags(config, api, 60000, 100)

        // Trigger flag evaluation - this will fail DNS lookup
        remoteConfig.getFeatureFlag(
            key = "test-flag",
            defaultValue = "default",
            distinctId = "test-user",
        )

        val error =
            remoteConfig.getFeatureFlagError(
                key = "test-flag",
                distinctId = "test-user",
            )

        assertEquals(FeatureFlagError.CONNECTION_ERROR, error)
    }

    @Test
    fun `getFeatureFlagError joins multiple errors with commas`() {
        val flagsResponse = createFlagsResponseWithErrors()
        val mockServer = createMockHttp(jsonResponse(flagsResponse))
        val url = mockServer.url("/")

        val config = createTestConfig(host = url.toString())
        val api = PostHogApi(config)
        val remoteConfig = PostHogFeatureFlags(config, api, 60000, 100)

        // Trigger flag evaluation to populate cache
        remoteConfig.getFeatureFlag(
            key = "missing-flag",
            defaultValue = "default",
            distinctId = "test-user",
        )

        val error =
            remoteConfig.getFeatureFlagError(
                key = "missing-flag",
                distinctId = "test-user",
            )

        assertEquals("${FeatureFlagError.ERRORS_WHILE_COMPUTING},${FeatureFlagError.FLAG_MISSING}", error)
        mockServer.shutdown()
    }

    @Test
    fun `getFeatureFlagError returns null when there are no errors`() {
        val flagsResponse = createFlagsResponse("test-flag", enabled = true)
        val mockServer = createMockHttp(jsonResponse(flagsResponse))
        val url = mockServer.url("/")

        val config = createTestConfig(host = url.toString())
        val api = PostHogApi(config)
        val remoteConfig = PostHogFeatureFlags(config, api, 60000, 100)

        // Trigger flag evaluation to populate cache
        remoteConfig.getFeatureFlag(
            key = "test-flag",
            defaultValue = "default",
            distinctId = "test-user",
        )

        val error =
            remoteConfig.getFeatureFlagError(
                key = "test-flag",
                distinctId = "test-user",
            )

        assertNull(error)
        mockServer.shutdown()
    }

    @Test
    fun `getFeatureFlagError returns null when distinctId is null`() {
        val config = createTestConfig()
        val api = PostHogApi(config)
        val remoteConfig = PostHogFeatureFlags(config, api, 60000, 100)

        val error =
            remoteConfig.getFeatureFlagError(
                key = "test-flag",
                distinctId = null,
            )

        assertNull(error)
    }

    @Test
    fun `getFeatureFlagError returns unknown_error when cache is empty`() {
        val config = createTestConfig()
        val api = PostHogApi(config)
        val remoteConfig = PostHogFeatureFlags(config, api, 60000, 100)

        val error =
            remoteConfig.getFeatureFlagError(
                key = "test-flag",
                distinctId = "test-user",
            )

        assertEquals(FeatureFlagError.UNKNOWN_ERROR, error)
    }
}
