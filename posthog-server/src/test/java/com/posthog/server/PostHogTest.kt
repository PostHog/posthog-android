package com.posthog.server

import com.posthog.PostHogStatelessInterface
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

internal class PostHogTest {
    private fun createMockStateless(): PostHogStatelessInterface {
        return mock()
    }

    private fun createPostHogWithMock(mockInstance: PostHogStatelessInterface): PostHog {
        val postHog = PostHog()

        // We need to mock PostHogStateless.with() to return our mock
        // Since we can't easily mock static methods, we'll test the delegation assuming setup works

        // Use reflection to set the private instance field for testing
        val instanceField = PostHog::class.java.getDeclaredField("instance")
        instanceField.isAccessible = true
        instanceField.set(postHog, mockInstance)

        return postHog
    }

    @Test
    fun `setup creates PostHogStateless instance with core config`() {
        val config = PostHogConfig(apiKey = TEST_API_KEY)
        val postHog = PostHog()

        postHog.setup(config)

        // Verify that setup doesn't throw and the instance is configured
        // We can't easily verify the internal state without reflection or mocking
        // but we can verify behavior through other methods
    }

    @Test
    fun `close delegates to instance close`() {
        val mockInstance = createMockStateless()
        val postHog = createPostHogWithMock(mockInstance)

        postHog.close()

        verify(mockInstance).close()
    }

    @Test
    fun `close handles null instance gracefully`() {
        val postHog = PostHog()

        // Should not throw when instance is null
        postHog.close()
    }

    @Test
    fun `identify delegates to instance with all parameters`() {
        val mockInstance = createMockStateless()
        val postHog = createPostHogWithMock(mockInstance)

        val userProperties = mapOf("name" to "John", "age" to 30)
        val userPropertiesSetOnce = mapOf("first_login" to true)

        postHog.identify("user123", userProperties, userPropertiesSetOnce)

        verify(mockInstance).identify("user123", userProperties, userPropertiesSetOnce)
    }

    @Test
    fun `identify handles null instance gracefully`() {
        val postHog = PostHog()

        // Should not throw when instance is null
        postHog.identify("user123", mapOf("name" to "John"), null)
    }

    @Test
    fun `flush delegates to instance`() {
        val mockInstance = createMockStateless()
        val postHog = createPostHogWithMock(mockInstance)

        postHog.flush()

        verify(mockInstance).flush()
    }

    @Test
    fun `flush handles null instance gracefully`() {
        val postHog = PostHog()

        // Should not throw when instance is null
        postHog.flush()
    }

    @Test
    fun `debug delegates to instance`() {
        val mockInstance = createMockStateless()
        val postHog = createPostHogWithMock(mockInstance)

        postHog.debug(true)

        verify(mockInstance).debug(true)
    }

    @Test
    fun `debug handles null instance gracefully`() {
        val postHog = PostHog()

        // Should not throw when instance is null
        postHog.debug(false)
    }

    @Test
    fun `capture delegates to instance captureStateless with all parameters`() {
        val mockInstance = createMockStateless()
        val postHog = createPostHogWithMock(mockInstance)

        val properties = mapOf("page" to "home")
        val userProperties = mapOf("plan" to "premium")
        val userPropertiesSetOnce = mapOf("signup_date" to "2023-01-01")
        val groups = mapOf("organization" to "acme")

        postHog.capture(
            distinctId = "user123",
            event = "page_view",
            properties = properties,
            userProperties = userProperties,
            userPropertiesSetOnce = userPropertiesSetOnce,
            groups = groups,
        )

        verify(mockInstance).captureStateless(
            "page_view",
            "user123",
            properties,
            userProperties,
            userPropertiesSetOnce,
            groups,
        )
    }

    @Test
    fun `capture handles null instance gracefully`() {
        val postHog = PostHog()

        // Should not throw when instance is null
        postHog.capture("user123", "test_event", mapOf("key" to "value"))
    }

    @Test
    fun `isFeatureEnabled delegates to instance and returns result`() {
        val mockInstance = createMockStateless()
        val postHog = createPostHogWithMock(mockInstance)

        whenever(mockInstance.isFeatureEnabledStateless("user123", "feature_key", true))
            .thenReturn(false)

        val result = postHog.isFeatureEnabled("user123", "feature_key", true)

        verify(mockInstance).isFeatureEnabledStateless("user123", "feature_key", true)
        assertFalse(result)
    }

    @Test
    fun `isFeatureEnabled returns false when instance is null`() {
        val postHog = PostHog()

        val result = postHog.isFeatureEnabled("user123", "feature_key", true)

        assertFalse(result)
    }

    @Test
    fun `getFeatureFlag delegates to instance and returns result`() {
        val mockInstance = createMockStateless()
        val postHog = createPostHogWithMock(mockInstance)

        whenever(mockInstance.getFeatureFlagStateless("user123", "feature_key", "default"))
            .thenReturn("variant_a")

        val result = postHog.getFeatureFlag("user123", "feature_key", "default")

        verify(mockInstance).getFeatureFlagStateless("user123", "feature_key", "default")
        assertEquals("variant_a", result)
    }

    @Test
    fun `getFeatureFlag returns null when instance is null`() {
        val postHog = PostHog()

        val result = postHog.getFeatureFlag("user123", "feature_key", "default")

        assertNull(result)
    }

    @Test
    fun `getFeatureFlagPayload delegates to instance and returns result`() {
        val mockInstance = createMockStateless()
        val postHog = createPostHogWithMock(mockInstance)

        val payloadData = mapOf("config" to "value")
        whenever(mockInstance.getFeatureFlagPayloadStateless("user123", "feature_key", null))
            .thenReturn(payloadData)

        val result = postHog.getFeatureFlagPayload("user123", "feature_key", null)

        verify(mockInstance).getFeatureFlagPayloadStateless("user123", "feature_key", null)
        assertEquals(payloadData, result)
    }

    @Test
    fun `getFeatureFlagPayload returns null when instance is null`() {
        val postHog = PostHog()

        val result = postHog.getFeatureFlagPayload("user123", "feature_key", "default")

        assertNull(result)
    }

    @Test
    fun `group delegates to instance groupStateless`() {
        val mockInstance = createMockStateless()
        val postHog = createPostHogWithMock(mockInstance)

        val groupProperties = mapOf("plan" to "enterprise", "size" to 100)

        postHog.group("user123", "organization", "acme_corp", groupProperties)

        verify(mockInstance).groupStateless("user123", "organization", "acme_corp", groupProperties)
    }

    @Test
    fun `group handles null instance gracefully`() {
        val postHog = PostHog()

        // Should not throw when instance is null
        postHog.group("user123", "organization", "acme_corp", mapOf("size" to 10))
    }

    @Test
    fun `alias delegates to instance aliasStateless`() {
        val mockInstance = createMockStateless()
        val postHog = createPostHogWithMock(mockInstance)

        postHog.alias("user123", "john_doe")

        verify(mockInstance).aliasStateless("user123", "john_doe")
    }

    @Test
    fun `alias handles null instance gracefully`() {
        val postHog = PostHog()

        // Should not throw when instance is null
        postHog.alias("user123", "john_doe")
    }

    @Test
    fun `with companion method creates and sets up PostHog instance`() {
        val config = PostHogConfig(apiKey = TEST_API_KEY, debug = true)

        val postHogInterface = PostHog.with(config)

        // Verify that we get a PostHogInterface back
        assertEquals(PostHog::class, postHogInterface::class)

        // The instance should be set up (we can't easily verify internal state,
        // but the method should complete without throwing)
    }

    @Test
    fun `with companion method works with different config types`() {
        val config =
            PostHogConfig(
                apiKey = "custom-key",
                host = "https://custom.host.com",
                debug = false,
                flushAt = 5,
            )

        val postHogInterface = PostHog.with(config)

        assertEquals(PostHog::class, postHogInterface::class)
    }

    @Test
    fun `capture with null parameters delegates correctly`() {
        val mockInstance = createMockStateless()
        val postHog = createPostHogWithMock(mockInstance)

        postHog.capture(
            distinctId = "user123",
            event = "simple_event",
            properties = null,
            userProperties = null,
            userPropertiesSetOnce = null,
            groups = null,
        )

        verify(mockInstance).captureStateless(
            "simple_event",
            "user123",
            null,
            null,
            null,
            null,
        )
    }

    @Test
    fun `identify with null parameters delegates correctly`() {
        val mockInstance = createMockStateless()
        val postHog = createPostHogWithMock(mockInstance)

        postHog.identify("user123", null, null)

        verify(mockInstance).identify("user123", null, null)
    }

    @Test
    fun `group with null groupProperties delegates correctly`() {
        val mockInstance = createMockStateless()
        val postHog = createPostHogWithMock(mockInstance)

        postHog.group("user123", "organization", "acme_corp", null)

        verify(mockInstance).groupStateless("user123", "organization", "acme_corp", null)
    }

    @Test
    fun `feature flag methods handle different return types`() {
        val mockInstance = createMockStateless()
        val postHog = createPostHogWithMock(mockInstance)

        // Test boolean feature flag
        whenever(mockInstance.isFeatureEnabledStateless("user123", "bool_flag", false))
            .thenReturn(true)

        // Test string feature flag
        whenever(mockInstance.getFeatureFlagStateless("user123", "string_flag", null))
            .thenReturn("variant_b")

        // Test numeric feature flag
        whenever(mockInstance.getFeatureFlagStateless("user123", "numeric_flag", 0))
            .thenReturn(42)

        val boolResult = postHog.isFeatureEnabled("user123", "bool_flag", false)
        val stringResult = postHog.getFeatureFlag("user123", "string_flag", null)
        val numericResult = postHog.getFeatureFlag("user123", "numeric_flag", 0)

        assertEquals(true, boolResult)
        assertEquals("variant_b", stringResult)
        assertEquals(42, numericResult)
    }

    @Test
    fun `all methods work correctly after setup`() {
        val config = PostHogConfig(apiKey = TEST_API_KEY)
        val postHog = PostHog()

        postHog.setup(config)

        // These should all complete without throwing exceptions
        // (We can't easily verify the actual behavior without mocking PostHogStateless.with())
        postHog.identify("user123")
        postHog.capture("user123", "test_event")
        postHog.group("user123", "org", "test")
        postHog.alias("user123", "alias")
        postHog.flush()
        postHog.debug(true)

        // Feature flag methods should return sensible defaults when no real instance
        val featureEnabled = postHog.isFeatureEnabled("user123", "test_flag")
        val featureFlag = postHog.getFeatureFlag("user123", "test_flag")
        val flagPayload = postHog.getFeatureFlagPayload("user123", "test_flag")

        // With no real setup, these should return defaults/nulls
        assertFalse(featureEnabled)
        assertNull(featureFlag)
        assertNull(flagPayload)

        postHog.close()
    }

    // Timestamp Tests
    @Test
    fun `capture with timestamp delegates to instance captureStateless with timestamp`() {
        val mockInstance = createMockStateless()
        val postHog = createPostHogWithMock(mockInstance)

        val timestamp = Date(1234567890L)
        val properties = mapOf("page" to "home")
        val userProperties = mapOf("plan" to "premium")
        val userPropertiesSetOnce = mapOf("signup_date" to "2023-01-01")
        val groups = mapOf("organization" to "acme")

        postHog.capture(
            distinctId = "user123",
            event = "page_view",
            properties = properties,
            userProperties = userProperties,
            userPropertiesSetOnce = userPropertiesSetOnce,
            groups = groups,
            timestamp = timestamp,
        )

        verify(mockInstance).captureStateless(
            "page_view",
            "user123",
            properties,
            userProperties,
            userPropertiesSetOnce,
            groups,
            timestamp,
        )
    }

    @Test
    fun `capture with null timestamp delegates correctly`() {
        val mockInstance = createMockStateless()
        val postHog = createPostHogWithMock(mockInstance)

        postHog.capture(
            distinctId = "user123",
            event = "test_event",
            properties = mapOf("key" to "value"),
            timestamp = null,
        )

        verify(mockInstance).captureStateless(
            "test_event",
            "user123",
            mapOf("key" to "value"),
            null,
            null,
            null,
            null,
        )
    }

    @Test
    fun `capture with PostHogCaptureOptions containing timestamp passes it through`() {
        val mockInstance = createMockStateless()
        val postHog = createPostHogWithMock(mockInstance)

        val timestamp = Date(1234567890L)
        val options =
            PostHogCaptureOptions.builder()
                .property("page", "home")
                .userProperty("plan", "premium")
                .timestamp(timestamp)
                .build()

        postHog.capture("user123", "page_view", options)

        verify(mockInstance).captureStateless(
            "page_view",
            "user123",
            options.properties,
            options.userProperties,
            options.userPropertiesSetOnce,
            options.groups,
            timestamp,
        )
    }

    @Test
    fun `capture with PostHogCaptureOptions without timestamp passes null timestamp`() {
        val mockInstance = createMockStateless()
        val postHog = createPostHogWithMock(mockInstance)

        val options =
            PostHogCaptureOptions.builder()
                .property("page", "home")
                .build()

        postHog.capture("user123", "page_view", options)

        verify(mockInstance).captureStateless(
            "page_view",
            "user123",
            options.properties,
            options.userProperties,
            options.userPropertiesSetOnce,
            options.groups,
            null,
        )
    }

    @Test
    fun `isFeatureEnabled propagates parameters as expected`() {
        val mockInstance = createMockStateless()
        val postHog = createPostHogWithMock(mockInstance)

        val groups = mapOf("organization" to "org_123")
        val personProperties = mapOf("plan" to "premium")
        val groupProperties = mapOf("org_123" to mapOf("size" to "large"))

        whenever(
            mockInstance.isFeatureEnabledStateless(
                "user123",
                "feature_key",
                true,
                groups,
                personProperties,
                groupProperties,
            ),
        ).thenReturn(false)

        val result =
            postHog.isFeatureEnabled(
                "user123",
                "feature_key",
                true,
                groups,
                personProperties,
                groupProperties,
            )

        verify(mockInstance).isFeatureEnabledStateless(
            "user123",
            "feature_key",
            true,
            groups,
            personProperties,
            groupProperties,
        )
        assertFalse(result)
    }

    @Test
    fun `isFeatureEnabled with PostHogFeatureFlagOptions propagates parameters as expected`() {
        val mockInstance = createMockStateless()
        val postHog = createPostHogWithMock(mockInstance)

        val options =
            PostHogFeatureFlagOptions.builder()
                .defaultValue(true)
                .group("organization", "org_123")
                .personProperty("plan", "premium")
                .groupProperty("org_123", "size", "large")
                .build()

        whenever(
            mockInstance.isFeatureEnabledStateless(
                "user123",
                "feature_key",
                true,
                options.groups,
                options.personProperties,
                options.groupProperties,
            ),
        ).thenReturn(false)

        val result = postHog.isFeatureEnabled("user123", "feature_key", options)

        verify(mockInstance).isFeatureEnabledStateless(
            "user123",
            "feature_key",
            true,
            options.groups,
            options.personProperties,
            options.groupProperties,
        )
        assertFalse(result)
    }

    @Test
    fun `isFeatureEnabled with PostHogFeatureFlagOptions handles non-boolean default value`() {
        val mockInstance = createMockStateless()
        val postHog = createPostHogWithMock(mockInstance)

        val options =
            PostHogFeatureFlagOptions.builder()
                .defaultValue("some_string")
                .group("organization", "org_123")
                .personProperty("plan", "premium")
                .groupProperty("org_123", "size", "large")
                .build()

        postHog.isFeatureEnabled("user123", "feature_key", options)

        verify(mockInstance).isFeatureEnabledStateless(
            "user123",
            "feature_key",
            false,
            options.groups,
            options.personProperties,
            options.groupProperties,
        )
    }

    @Test
    fun `getFeatureFlag propagates parameters as expected`() {
        val mockInstance = createMockStateless()
        val postHog = createPostHogWithMock(mockInstance)

        val groups = mapOf("organization" to "org_123")
        val personProperties = mapOf("plan" to "premium")
        val groupProperties = mapOf("org_123" to mapOf("size" to "large"))

        postHog.getFeatureFlag(
            "user123",
            "feature_key",
            "default",
            groups,
            personProperties,
            groupProperties,
        )

        verify(mockInstance).getFeatureFlagStateless(
            "user123",
            "feature_key",
            "default",
            groups,
            personProperties,
            groupProperties,
        )
    }

    @Test
    fun `getFeatureFlag with PostHogFeatureFlagOptions propagates parameters as expected`() {
        val mockInstance = createMockStateless()
        val postHog = createPostHogWithMock(mockInstance)

        val options =
            PostHogFeatureFlagOptions.builder()
                .defaultValue("default")
                .group("organization", "org_123")
                .personProperty("plan", "premium")
                .groupProperty("org_123", "size", "large")
                .build()

        postHog.getFeatureFlag("user123", "feature_key", options)

        verify(mockInstance).getFeatureFlagStateless(
            "user123",
            "feature_key",
            "default",
            options.groups,
            options.personProperties,
            options.groupProperties,
        )
    }

    @Test
    fun `getFeatureFlagPayload propagates parameters as expected`() {
        val mockInstance = createMockStateless()
        val postHog = createPostHogWithMock(mockInstance)

        val groups = mapOf("organization" to "org_123")
        val personProperties = mapOf("plan" to "premium")
        val groupProperties = mapOf("org_123" to mapOf("size" to "large"))

        postHog.getFeatureFlagPayload(
            "user123",
            "feature_key",
            null,
            groups,
            personProperties,
            groupProperties,
        )

        verify(mockInstance).getFeatureFlagPayloadStateless(
            "user123",
            "feature_key",
            null,
            groups,
            personProperties,
            groupProperties,
        )
    }

    @Test
    fun `getFeatureFlagPayload with PostHogFeatureFlagOptions propagates parameters as expected`() {
        val mockInstance = createMockStateless()
        val postHog = createPostHogWithMock(mockInstance)

        val options =
            PostHogFeatureFlagOptions.builder()
                .defaultValue(null)
                .group("organization", "org_123")
                .personProperty("plan", "premium")
                .groupProperty("org_123", "size", "large")
                .build()

        postHog.getFeatureFlagPayload("user123", "feature_key", options)

        verify(mockInstance).getFeatureFlagPayloadStateless(
            "user123",
            "feature_key",
            null,
            options.groups,
            options.personProperties,
            options.groupProperties,
        )
    }
}
