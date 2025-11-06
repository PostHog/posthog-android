package com.posthog.server

import com.posthog.server.internal.PostHogFeatureFlags
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

internal class PostHogTest {
    private fun createMockStateless(): PostHog {
        return spy(PostHog())
    }

    private fun createPostHogWithMock(mockInstance: PostHog): PostHog {
        return mockInstance
    }

    @Test
    fun `setup creates PostHogStateless instance with core config`() {
        val config = PostHogConfig(apiKey = TEST_API_KEY)
        val postHog = PostHog()

        postHog.setup(config)

        // Verify that setup doesn't throw and the instance is configured
        // The actual functionality is tested in PostHogStateless tests
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
    fun `all methods work correctly after setup`() {
        val config = PostHogConfig(apiKey = TEST_API_KEY)
        val postHog = PostHog()

        postHog.setup(config)

        // These should all complete without throwing exceptions
        postHog.identify("user123")
        postHog.capture("user123", "test_event")
        postHog.group("user123", "org", "test")
        postHog.alias("user123", "alias")
        postHog.flush()
        postHog.debug(true)

        // Feature flag methods should work
        val featureEnabled = postHog.isFeatureEnabled("user123", "test_flag")
        val featureFlag = postHog.getFeatureFlag("user123", "test_flag")
        val flagPayload = postHog.getFeatureFlagPayload("user123", "test_flag")

        // With no remote config, these should return defaults
        assertFalse(featureEnabled)
        assertNull(featureFlag)
        assertNull(flagPayload)

        postHog.close()
    }

    @Test
    fun `capture with timestamp passes timestamp through`() {
        val config = PostHogConfig(apiKey = TEST_API_KEY)
        val postHog = PostHog()
        postHog.setup(config)

        val timestamp = java.util.Date(1234567890L)

        // Should not throw
        postHog.capture(
            distinctId = "user123",
            event = "test_event",
            properties = mapOf("key" to "value"),
            timestamp = timestamp,
        )

        postHog.close()
    }

    @Test
    fun `capture with PostHogCaptureOptions works correctly`() {
        val config = PostHogConfig(apiKey = TEST_API_KEY)
        val postHog = PostHog()
        postHog.setup(config)

        val options =
            PostHogCaptureOptions.builder()
                .property("page", "home")
                .userProperty("plan", "premium")
                .build()

        // Should not throw
        postHog.capture("user123", "page_view", options)

        postHog.close()
    }

    @Test
    fun `isFeatureEnabled with PostHogFeatureFlagOptions works correctly`() {
        val config = PostHogConfig(apiKey = TEST_API_KEY)
        val postHog = PostHog()
        postHog.setup(config)

        val options =
            PostHogFeatureFlagOptions.builder()
                .defaultValue(true)
                .group("organization", "org_123")
                .personProperty("plan", "premium")
                .groupProperty("org_123", "size", "large")
                .build()

        val result = postHog.isFeatureEnabled("user123", "feature_key", options)

        // Result depends on whether feature flags are loaded, but should not throw
        assertNotNull(result)

        postHog.close()
    }

    @Test
    fun `getFeatureFlag with PostHogFeatureFlagOptions works correctly`() {
        val config = PostHogConfig(apiKey = TEST_API_KEY)
        val postHog = PostHog()
        postHog.setup(config)

        val options =
            PostHogFeatureFlagOptions.builder()
                .defaultValue("default")
                .group("organization", "org_123")
                .personProperty("plan", "premium")
                .groupProperty("org_123", "size", "large")
                .build()

        // Should not throw
        postHog.getFeatureFlag("user123", "feature_key", options)

        postHog.close()
    }

    @Test
    fun `getFeatureFlagPayload with PostHogFeatureFlagOptions works correctly`() {
        val config = PostHogConfig(apiKey = TEST_API_KEY)
        val postHog = PostHog()
        postHog.setup(config)

        val options =
            PostHogFeatureFlagOptions.builder()
                .defaultValue(null)
                .group("organization", "org_123")
                .personProperty("plan", "premium")
                .groupProperty("org_123", "size", "large")
                .build()

        // Should not throw
        postHog.getFeatureFlagPayload("user123", "feature_key", options)

        postHog.close()
    }

    @Test
    fun `PostHog implements PostHogInterface correctly`() {
        val config = PostHogConfig(apiKey = TEST_API_KEY)
        val postHogInterface: PostHogInterface = PostHog.with(config)

        // Verify the interface is implemented correctly and we get the right type
        val postHog = postHogInterface as? PostHog
        assertNotNull(postHog)

        postHogInterface.close()
    }

    @Test
    fun `reloadFeatureFlags calls loadFeatureFlagDefinitions on PostHogFeatureFlags`() {
        val postHog = spy(PostHog())

        // Set up a mock feature flags instance
        val mockFeatureFlags = mock<PostHogFeatureFlags>()

        // Use reflection to set the featureFlags field for testing
        val featureFlagsField = postHog.javaClass.superclass.getDeclaredField("featureFlags")
        featureFlagsField.isAccessible = true
        featureFlagsField.set(postHog, mockFeatureFlags)

        // Call reloadFeatureFlags
        postHog.reloadFeatureFlags()

        // Verify that loadFeatureFlagDefinitions was called on the mock
        verify(mockFeatureFlags).loadFeatureFlagDefinitions()
    }

    @Test
    fun `reloadFeatureFlags handles null featureFlags gracefully`() {
        val postHog = PostHog()

        // Should not throw when featureFlags is null
        postHog.reloadFeatureFlags()
    }

    @Test
    fun `reloadFeatureFlags handles non-PostHogFeatureFlags implementation gracefully`() {
        val postHog = spy(PostHog())

        // Set up a different implementation of the feature flags interface
        val mockFeatureFlags = mock<com.posthog.internal.PostHogFeatureFlagsInterface>()

        // Use reflection to set the featureFlags field
        val featureFlagsField = postHog.javaClass.superclass.getDeclaredField("featureFlags")
        featureFlagsField.isAccessible = true
        featureFlagsField.set(postHog, mockFeatureFlags)

        // Should not throw - the cast will fail but be handled
        postHog.reloadFeatureFlags()
    }

    @Test
    fun `captureException delegates to instance captureExceptionStateless with all parameters`() {
        val postHog = spy(PostHog())

        val exception = RuntimeException("Test exception")
        val properties = mapOf("context" to "test", "severity" to "high")
        val distinctId = "user123"

        postHog.captureException(exception, distinctId, properties)

        verify(postHog).captureExceptionStateless(exception, distinctId, properties)
    }

    @Test
    fun `captureException overloads`() {
        val postHog = spy(PostHog())
        val exception = RuntimeException("Test exception")
        val properties = mapOf("context" to "test")
        val distinctId = "user123"

        postHog.captureException(exception)
        postHog.captureException(exception, distinctId)
        postHog.captureException(exception, properties)
        postHog.captureException(exception, distinctId, properties)

        verify(postHog).captureExceptionStateless(exception, null, null)
        verify(postHog).captureExceptionStateless(exception, distinctId, null)
        verify(postHog).captureExceptionStateless(exception, null, properties)
        verify(postHog).captureExceptionStateless(exception, distinctId, properties)
    }
}
