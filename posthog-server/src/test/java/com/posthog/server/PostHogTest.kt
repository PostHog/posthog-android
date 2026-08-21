package com.posthog.server

import com.posthog.internal.PostHogFeatureFlagsInterface
import com.posthog.server.internal.FeatureFlagError
import com.posthog.server.internal.PostHogFeatureFlags
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("DEPRECATION")
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
    fun `getFeatureFlagResult works correctly`() {
        val config = PostHogConfig(apiKey = TEST_API_KEY)
        val postHog = PostHog()
        postHog.setup(config)

        // With no remote config, should return null
        val result = postHog.getFeatureFlagResult("user123", "test_flag")
        assertNull(result)

        postHog.close()
    }

    @Test
    fun `getFeatureFlagResult with PostHogFeatureFlagOptions works correctly`() {
        val config = PostHogConfig(apiKey = TEST_API_KEY)
        val postHog = PostHog()
        postHog.setup(config)

        val options =
            PostHogFeatureFlagResultOptions.builder()
                .group("organization", "org_123")
                .personProperty("plan", "premium")
                .groupProperty("org_123", "size", "large")
                .build()

        // Should not throw
        val result = postHog.getFeatureFlagResult("user123", "feature_key", options)

        // With no remote config, should return null
        assertNull(result)

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
        val mockFeatureFlags = mock<PostHogFeatureFlagsInterface>()

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

    @Test
    fun `captureException stamps frames with map_id when releaseIdentifier is set`() {
        val mockServer = MockWebServer()
        mockServer.enqueue(MockResponse().setResponseCode(200))
        mockServer.start()

        val url = mockServer.url("/").toString()
        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(url)
                    .flushAt(1)
                    .releaseIdentifier("release-123")
                    .build(),
            )

        postHog.captureException(RuntimeException("boom"), "user123")

        val request = mockServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(request, "Expected /batch request within 5 seconds")

        val props = request.parseBatch().firstEventProperties()
        val exceptionList = props["\$exception_list"] as List<*>
        val stacktrace = (exceptionList.first() as Map<*, *>)["stacktrace"] as Map<*, *>
        val frames = stacktrace["frames"] as List<*>
        assertTrue(frames.isNotEmpty())
        frames.forEach { frame ->
            assertEquals("release-123", (frame as Map<*, *>)["map_id"])
        }

        postHog.close()
        mockServer.shutdown()
    }

    @Test
    fun `close flushes pending events before tearing down`() {
        val mockServer = MockWebServer()
        mockServer.enqueue(MockResponse().setResponseCode(200))
        mockServer.start()

        val url = mockServer.url("/").toString()
        // default flushAt is 20, so this single captured event stays queued
        // instead of auto-flushing on capture; only close() should deliver it
        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(url)
                    .build(),
            )

        postHog.capture("user123", "test_event")

        postHog.close()

        assertEquals(1, mockServer.requestCount, "Expected /batch request before close() returned")

        mockServer.shutdown()
    }

    @Test
    fun `capture with appendFeatureFlags false does not enrich properties`() {
        val mockServer = MockWebServer()
        mockServer.enqueue(MockResponse().setResponseCode(200))
        mockServer.start()

        val url = mockServer.url("/").toString()
        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(url)
                    .flushAt(1)
                    .build(),
            )

        postHog.capture(
            "user123",
            "test_event",
            PostHogCaptureOptions.builder()
                .property("custom", "value")
                .appendFeatureFlags(false)
                .build(),
        )

        val request = mockServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(request, "Expected /batch request within 5 seconds")

        val props = request.parseBatch().firstEventProperties()
        assertFalse(
            props.keys.any { it.startsWith("\$feature/") },
            "Event should not contain \$feature/ properties when appendFeatureFlags is false",
        )
        assertFalse(
            props.containsKey("\$active_feature_flags"),
            "Event should not contain \$active_feature_flags when appendFeatureFlags is false",
        )
        assertEquals("value", props["custom"])

        postHog.close()
        mockServer.shutdown()
    }

    @Test
    fun `capture with appendFeatureFlags true enriches event with feature flag properties`() {
        val localEvalResponse = createLocalEvaluationResponse("test-flag")
        val mockServer = MockWebServer()
        mockServer.enqueue(jsonResponse(localEvalResponse))
        mockServer.enqueue(MockResponse().setResponseCode(200))
        mockServer.start()

        val url = mockServer.url("/").toString()
        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(url)
                    .personalApiKey("phx_test_personal_api_key")
                    .flushAt(1)
                    .build(),
            )

        postHog.capture(
            "user123",
            "test_event",
            PostHogCaptureOptions.builder()
                .property("custom", "value")
                .appendFeatureFlags(true)
                .build(),
        )

        // Skip /local_evaluation request
        mockServer.takeRequest(5, TimeUnit.SECONDS)

        val batchRequest = mockServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(batchRequest, "Expected /batch request within 5 seconds")

        val props = batchRequest.parseBatch().firstEventProperties()

        @Suppress("UNCHECKED_CAST")
        val activeFlags = props["\$active_feature_flags"] as? List<String>

        assertEquals(true, props["\$feature/test-flag"])
        assertNotNull(activeFlags, "Expected \$active_feature_flags to be present")
        assertTrue(activeFlags.contains("test-flag"))
        assertEquals("value", props["custom"])

        postHog.close()
        mockServer.shutdown()
    }

    @Test
    fun `capture with appendFeatureFlags uses local evaluation and does not call flags endpoint`() {
        val localEvalResponse = createLocalEvaluationResponse("test-flag")
        val mockServer = MockWebServer()
        mockServer.enqueue(jsonResponse(localEvalResponse))
        mockServer.enqueue(MockResponse().setResponseCode(200))
        mockServer.start()

        val url = mockServer.url("/").toString()
        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(url)
                    .personalApiKey("phx_test_personal_api_key")
                    .flushAt(1)
                    .build(),
            )

        postHog.capture(
            distinctId = "user123",
            event = "test_event",
            properties = mapOf("custom" to "value"),
            appendFeatureFlags = true,
        )

        // Collect all requests (use longer timeout for CI)
        val requests = mutableListOf<RecordedRequest>()
        var request = mockServer.takeRequest(5, TimeUnit.SECONDS)
        while (request != null) {
            requests.add(request)
            request = mockServer.takeRequest(2, TimeUnit.SECONDS)
        }

        assertTrue(
            requests.any { it.path?.contains("/local_evaluation") == true },
            "Expected /local_evaluation to be called",
        )
        assertTrue(
            requests.any { it.path?.contains("/batch") == true },
            "Expected /batch to be called",
        )
        assertFalse(
            requests.any { it.path?.contains("/flags") == true },
            "Expected /flags to NOT be called when local evaluation is enabled",
        )

        postHog.close()
        mockServer.shutdown()
    }

    @Test
    fun `empty api key disables SDK`() {
        val postHog = PostHog()

        postHog.setup(PostHogConfig.builder(" \n\t ").build())

        assertTrue(postHog.isOptOut())

        postHog.close()
    }

    @Test
    fun `capture with appendFeatureFlags without local evaluation calls flags endpoint`() {
        val flagsResponse = createFlagsResponse("test-flag")
        val mockServer = MockWebServer()
        mockServer.enqueue(jsonResponse(flagsResponse))
        mockServer.enqueue(MockResponse().setResponseCode(200))
        mockServer.start()

        val url = mockServer.url("/").toString()
        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(url)
                    .flushAt(1)
                    .build(),
            )

        postHog.capture(
            distinctId = "user123",
            event = "test_event",
            properties = mapOf("custom" to "value"),
            appendFeatureFlags = true,
        )

        val requests = mutableListOf<RecordedRequest>()
        var request = mockServer.takeRequest(5, TimeUnit.SECONDS)
        while (request != null) {
            requests.add(request)
            request = mockServer.takeRequest(2, TimeUnit.SECONDS)
        }

        assertTrue(
            requests.any { it.path?.contains("/flags") == true },
            "Expected /flags to be called when local evaluation is not enabled",
        )
        assertFalse(
            requests.any {
                it.path?.contains("/local_evaluation") == true
            },
            "Expected /local_evaluation to NOT be called without personalApiKey",
        )
        assertTrue(
            requests.any { it.path?.contains("/batch") == true },
            "Expected /batch to be called",
        )

        postHog.close()
        mockServer.shutdown()
    }

    @Test
    fun `capture with appendFeatureFlags includes truthy flags in active_feature_flags and excludes falsy`() {
        val localEvalResponse =
            """
            {
                "flags": [
                    {
                        "id": 1,
                        "name": "enabled-flag",
                        "key": "enabled-flag",
                        "active": true,
                        "filters": {
                            "groups": [{ "properties": [], "rollout_percentage": 100 }]
                        },
                        "version": 1
                    },
                    {
                        "id": 2,
                        "name": "disabled-flag",
                        "key": "disabled-flag",
                        "active": false,
                        "filters": {
                            "groups": [{ "properties": [], "rollout_percentage": 100 }]
                        },
                        "version": 1
                    },
                    {
                        "id": 3,
                        "name": "variant-flag",
                        "key": "variant-flag",
                        "active": true,
                        "filters": {
                            "multivariate": {
                                "variants": [
                                    { "key": "control", "rollout_percentage": 0 },
                                    { "key": "test-variant", "rollout_percentage": 100 }
                                ]
                            },
                            "groups": [{ "properties": [], "rollout_percentage": 100 }]
                        },
                        "version": 1
                    }
                ],
                "group_type_mapping": {},
                "cohorts": {}
            }
            """.trimIndent()

        val mockServer = MockWebServer()
        mockServer.enqueue(jsonResponse(localEvalResponse))
        mockServer.enqueue(MockResponse().setResponseCode(200))
        mockServer.start()

        val url = mockServer.url("/").toString()
        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(url)
                    .personalApiKey("phx_test_personal_api_key")
                    .flushAt(1)
                    .build(),
            )

        postHog.capture(
            distinctId = "user123",
            event = "test_event",
            appendFeatureFlags = true,
        )

        // Skip /local_evaluation request
        mockServer.takeRequest(5, TimeUnit.SECONDS)

        val batchRequest = mockServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(batchRequest, "Expected /batch request within 5 seconds")

        val props = batchRequest.parseBatch().firstEventProperties()

        @Suppress("UNCHECKED_CAST")
        val activeFlags = props["\$active_feature_flags"] as? List<String>

        assertEquals(true, props["\$feature/enabled-flag"])
        assertEquals(false, props["\$feature/disabled-flag"])
        assertEquals("test-variant", props["\$feature/variant-flag"])
        assertNotNull(activeFlags, "Expected \$active_feature_flags to be present")
        assertTrue(activeFlags.contains("enabled-flag"), "enabled-flag should be in active flags")
        assertTrue(activeFlags.contains("variant-flag"), "variant-flag should be in active flags")
        assertFalse(activeFlags.contains("disabled-flag"), "disabled-flag should NOT be in active flags")

        postHog.close()
        mockServer.shutdown()
    }

    @Test
    fun `feature flag API error sends feature_flag_called event with feature_flag_error to batch`() {
        val mockServer = MockWebServer()
        // First request: /flags returns 500 error
        mockServer.enqueue(errorResponse(500, "Internal Server Error"))
        // Second request: /batch returns success
        mockServer.enqueue(MockResponse().setResponseCode(200))
        mockServer.start()

        val url = mockServer.url("/").toString()
        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(url)
                    .flushAt(1)
                    .build(),
            )

        // This will hit /flags (which returns 500) and then send $feature_flag_called to /batch
        postHog.getFeatureFlag("user123", "test-flag")

        // First request should be /flags
        val flagsRequest = mockServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(flagsRequest, "Expected /flags request")
        assertTrue(flagsRequest.path?.contains("/flags") == true, "First request should be /flags")

        // Second request should be /batch with $feature_flag_called event
        val batchRequest = mockServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(batchRequest, "Expected /batch request within 5 seconds")
        assertTrue(batchRequest.path?.contains("/batch") == true, "Second request should be /batch")

        val batch = batchRequest.parseBatch()
        val featureFlagEvent = batch.findEvent("\$feature_flag_called")
        assertNotNull(featureFlagEvent, "Expected \$feature_flag_called event in batch")

        val props = batch.eventProperties("\$feature_flag_called")
        assertEquals("test-flag", props["\$feature_flag"])
        assertEquals(FeatureFlagError.apiError(500), props["\$feature_flag_error"])

        postHog.close()
        mockServer.shutdown()
    }

    /**
     * `ignoredExceptionTypes` has no server-config builder yet, so configure it on the core config
     * the server config produces — the same object the capture path reads at runtime.
     */
    private fun postHogWithIgnoredTypes(
        url: String,
        vararg ignored: Class<out Throwable>,
    ): PostHog {
        val coreConfig =
            PostHogConfig.builder(TEST_API_KEY)
                .host(url)
                .flushAt(1)
                .build()
                .asCoreConfig()
        coreConfig.errorTrackingConfig.ignoredExceptionTypes.addAll(ignored)
        return PostHog().apply { setup(coreConfig) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun framesOf(props: Map<String, Any?>): List<Map<String, Any?>> {
        val exceptionList = props["\$exception_list"] as List<Map<String, Any?>>
        val stacktrace = exceptionList.first()["stacktrace"] as Map<String, Any?>
        return stacktrace["frames"] as List<Map<String, Any?>>
    }

    @Test
    fun `captureException with options merges groups, flag and custom properties, and stamps frames`() {
        val mockServer = MockWebServer()
        mockServer.enqueue(jsonResponse(createLocalEvaluationResponse("test-flag")))
        mockServer.enqueue(MockResponse().setResponseCode(200))
        mockServer.start()

        val url = mockServer.url("/").toString()
        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(url)
                    .personalApiKey("phx_test_personal_api_key")
                    .flushAt(1)
                    .inAppIncludes(listOf("com.posthog.server"))
                    .inAppExcludes(listOf("org.", "jdk.", "java."))
                    .releaseIdentifier("posthog-server@1.0.0")
                    .build(),
            )

        postHog.captureException(
            RuntimeException("boom"),
            "user123",
            PostHogCaptureOptions.builder()
                .property("custom", "value")
                .group("company", "acme")
                .appendFeatureFlags(true)
                .build(),
        )

        // Skip /local_evaluation request
        mockServer.takeRequest(5, TimeUnit.SECONDS)

        val batchRequest = mockServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(batchRequest, "Expected /batch request within 5 seconds")

        val batch = batchRequest.parseBatch()
        assertNotNull(batch.findEvent("\$exception"), "Expected \$exception event in batch")

        val props = batch.eventProperties("\$exception")
        assertEquals("value", props["custom"])
        assertEquals(true, props["\$feature/test-flag"])

        @Suppress("UNCHECKED_CAST")
        val groups = props["\$groups"] as Map<String, Any?>
        assertEquals("acme", groups["company"])

        val frames = framesOf(props)
        assertTrue(frames.isNotEmpty())
        frames.forEach { frame -> assertEquals("posthog-server@1.0.0", frame["map_id"]) }

        val inAppFrames = frames.filter { it["in_app"] == true }
        val notInAppFrames = frames.filter { it["in_app"] == false }
        assertTrue(inAppFrames.isNotEmpty(), "Expected in-app frames from com.posthog.server")
        assertTrue(
            inAppFrames.all { (it["module"] as String).startsWith("com.posthog.server") },
            "Only frames matching inAppIncludes should be in-app",
        )
        assertTrue(notInAppFrames.isNotEmpty(), "Expected frames outside inAppIncludes to not be in-app")

        postHog.close()
        mockServer.shutdown()
    }

    @Test
    fun `captureException with flags snapshot attaches flag properties without another flags request`() {
        val mockServer = MockWebServer()
        mockServer.enqueue(jsonResponse(createFlagsResponse("test-flag")))
        mockServer.enqueue(MockResponse().setResponseCode(200))
        mockServer.start()

        val url = mockServer.url("/").toString()
        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(url)
                    .flushAt(1)
                    .build(),
            )

        val snapshot = postHog.evaluateFlags("user123")

        postHog.captureException(
            RuntimeException("boom"),
            "user123",
            PostHogCaptureOptions.builder()
                .flags(snapshot)
                .build(),
        )

        val flagsRequest = mockServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(flagsRequest, "Expected /flags request within 5 seconds")
        assertTrue(flagsRequest.path?.contains("/flags") == true, "First request should be /flags")

        val batchRequest = mockServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(batchRequest, "Expected /batch request within 5 seconds")
        assertTrue(batchRequest.path?.contains("/batch") == true, "Second request should be /batch")

        val props = batchRequest.parseBatch().eventProperties("\$exception")
        assertEquals(true, props["\$feature/test-flag"])

        postHog.close()
        mockServer.shutdown()
    }

    @Test
    fun `captureException with options allows overriding exception level via properties`() {
        val mockServer = MockWebServer()
        mockServer.enqueue(MockResponse().setResponseCode(200))
        mockServer.start()

        val url = mockServer.url("/").toString()
        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(url)
                    .flushAt(1)
                    .build(),
            )

        postHog.captureException(
            RuntimeException("boom"),
            "user123",
            PostHogCaptureOptions.builder()
                .property("\$exception_level", "warning")
                .property("\$exception_fingerprint", "custom-fingerprint")
                .build(),
        )

        val batchRequest = mockServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(batchRequest, "Expected /batch request within 5 seconds")

        val props = batchRequest.parseBatch().eventProperties("\$exception")
        assertEquals("warning", props["\$exception_level"])
        assertEquals("custom-fingerprint", props["\$exception_fingerprint"])

        postHog.close()
        mockServer.shutdown()
    }

    @Test
    fun `captureException with options and no distinct id stays personless`() {
        val mockServer = MockWebServer()
        mockServer.enqueue(MockResponse().setResponseCode(200))
        mockServer.start()

        val url = mockServer.url("/").toString()
        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(url)
                    .flushAt(1)
                    .build(),
            )

        postHog.captureException(
            RuntimeException("boom"),
            PostHogCaptureOptions.builder()
                .property("custom", "value")
                .build(),
        )

        val batchRequest = mockServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(batchRequest, "Expected /batch request within 5 seconds")

        val batch = batchRequest.parseBatch()
        val event = batch.findEvent("\$exception")
        assertNotNull(event, "Expected \$exception event in batch")
        val eventDistinctId = event.get("distinct_id").asString
        assertTrue(
            eventDistinctId.matches("[0-9a-fA-F-]{36}".toRegex()),
            "Expected a generated UUID distinct id, got $eventDistinctId",
        )

        val props = batch.eventProperties("\$exception")
        assertEquals(false, props["\$process_person_profile"])
        assertEquals("value", props["custom"])

        postHog.close()
        mockServer.shutdown()
    }

    @Test
    fun `captureException with default config splits in-app frames by DEFAULT_IN_APP_EXCLUDES`() {
        val mockServer = MockWebServer()
        mockServer.enqueue(MockResponse().setResponseCode(200))
        mockServer.start()

        val url = mockServer.url("/").toString()
        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(url)
                    .flushAt(1)
                    .build(),
            )

        postHog.captureException(RuntimeException("boom"), "user123")

        val batchRequest = mockServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(batchRequest, "Expected /batch request within 5 seconds")

        val props = batchRequest.parseBatch().eventProperties("\$exception")
        val frames = framesOf(props)
        assertTrue(frames.isNotEmpty())

        val sdkFrames = frames.filter { (it["module"] as String).startsWith("com.posthog.") }
        assertTrue(sdkFrames.isNotEmpty(), "Expected frames from the test class itself")
        assertTrue(
            sdkFrames.all { it["in_app"] == false },
            "Frames matching DEFAULT_IN_APP_EXCLUDES (com.posthog.) should not be in-app",
        )
        assertTrue(
            frames.any { it["in_app"] == true },
            "Frames not matching any default exclude (e.g. junit/gradle) should stay in-app",
        )
        assertTrue(
            frames.none { it.containsKey("map_id") },
            "map_id should be absent when releaseIdentifier is not configured",
        )

        postHog.close()
        mockServer.shutdown()
    }

    @Test
    fun `captureException options overload without distinct id delegates to canonical overload`() {
        val postHog = spy(PostHog())
        val exception = RuntimeException("Test exception")
        val options = PostHogCaptureOptions.builder().property("k", "v").build()

        postHog.captureException(exception, options)

        verify(postHog).captureException(exception, null, options)
    }

    @Test
    fun `captureException with options drops throwables matching ignoredExceptionTypes`() {
        val mockServer = MockWebServer()
        mockServer.enqueue(MockResponse().setResponseCode(200))
        mockServer.start()

        val postHog = postHogWithIgnoredTypes(mockServer.url("/").toString(), IllegalStateException::class.java)

        postHog.captureException(
            IllegalStateException("suppressed"),
            "user123",
            PostHogCaptureOptions.builder().property("marker", "suppressed").build(),
        )

        assertNull(
            mockServer.takeRequest(500, TimeUnit.MILLISECONDS),
            "An ignored exception must not reach /batch through the options overload",
        )

        // control: the same overload still ships a type that is not ignored
        postHog.captureException(
            RuntimeException("kept"),
            "user123",
            PostHogCaptureOptions.builder().property("marker", "kept").build(),
        )

        val batchRequest = mockServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(batchRequest, "Expected /batch request within 5 seconds")
        assertEquals("kept", batchRequest.parseBatch().eventProperties("\$exception")["marker"])

        postHog.close()
        mockServer.shutdown()
    }

    @Test
    fun `captureException with options drops a throwable whose cause matches ignoredExceptionTypes`() {
        val mockServer = MockWebServer()
        mockServer.enqueue(MockResponse().setResponseCode(200))
        mockServer.start()

        val postHog = postHogWithIgnoredTypes(mockServer.url("/").toString(), IllegalStateException::class.java)

        postHog.captureException(
            RuntimeException("outer", IllegalStateException("ignored cause")),
            "user123",
            PostHogCaptureOptions.builder().property("marker", "suppressed").build(),
        )

        assertNull(
            mockServer.takeRequest(500, TimeUnit.MILLISECONDS),
            "An ignored type anywhere in the cause chain must not reach /batch",
        )

        postHog.close()
        mockServer.shutdown()
    }

    @Test
    fun `captureException with options merges options for types outside ignoredExceptionTypes`() {
        val mockServer = MockWebServer()
        mockServer.enqueue(MockResponse().setResponseCode(200))
        mockServer.start()

        val postHog = postHogWithIgnoredTypes(mockServer.url("/").toString(), IllegalStateException::class.java)

        postHog.captureException(
            RuntimeException("boom"),
            "user123",
            PostHogCaptureOptions.builder()
                .property("custom", "value")
                .property("\$exception_level", "warning")
                .group("company", "acme")
                .userProperty("plan", "enterprise")
                .userPropertySetOnce("signup", "2020")
                .build(),
        )

        val batchRequest = mockServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(batchRequest, "Expected /batch request within 5 seconds")

        val props = batchRequest.parseBatch().eventProperties("\$exception")
        assertEquals("value", props["custom"])
        assertEquals("warning", props["\$exception_level"])
        assertNotNull(props["\$exception_list"], "The coerced exception properties should still be present")

        @Suppress("UNCHECKED_CAST")
        val groups = props["\$groups"] as Map<String, Any?>
        assertEquals("acme", groups["company"])

        // person updates are dropped by the error-tracking ingestion pipeline, so the SDK must not
        // pretend otherwise by putting them on the wire
        assertNull(props["\$set"], "\$exception events must not carry \$set")
        assertNull(props["\$set_once"], "\$exception events must not carry \$set_once")

        postHog.close()
        mockServer.shutdown()
    }

    @Test
    fun `captureException with options does not evaluate flags for an ignored throwable`() {
        val mockServer = MockWebServer()
        mockServer.enqueue(MockResponse().setResponseCode(200))
        mockServer.start()

        val postHog = postHogWithIgnoredTypes(mockServer.url("/").toString(), IllegalStateException::class.java)

        postHog.captureException(
            IllegalStateException("suppressed"),
            "user123",
            PostHogCaptureOptions.builder().appendFeatureFlags(true).build(),
        )

        assertNull(
            mockServer.takeRequest(500, TimeUnit.MILLISECONDS),
            "An ignored exception must not fire a /flags request nor a \$exception event",
        )

        // control: the same overload with the same options still enriches and ships a kept type,
        // so the assertion above is about the ignore gate and not about a dead client
        postHog.captureException(
            RuntimeException("kept"),
            "user123",
            PostHogCaptureOptions.builder().appendFeatureFlags(true).build(),
        )

        val flagsRequest = mockServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(flagsRequest, "Expected /flags request for the kept exception")
        assertTrue(flagsRequest.path?.contains("/flags") == true, "First request should be /flags")

        val batchRequest = mockServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(batchRequest, "Expected /batch request within 5 seconds")
        assertNotNull(batchRequest.parseBatch().findEvent("\$exception"), "Expected \$exception event in batch")

        postHog.close()
        mockServer.shutdown()
    }

    @Test
    fun `captureException with options sends nothing when the client is opted out`() {
        val mockServer = MockWebServer()
        mockServer.enqueue(MockResponse().setResponseCode(200))
        mockServer.start()

        val coreConfig =
            PostHogConfig.builder(TEST_API_KEY)
                .host(mockServer.url("/").toString())
                .flushAt(1)
                .build()
                .asCoreConfig()
        coreConfig.optOut = true
        val postHog = PostHog().apply { setup(coreConfig) }

        postHog.captureException(
            RuntimeException("boom"),
            "user123",
            PostHogCaptureOptions.builder().appendFeatureFlags(true).build(),
        )

        assertNull(
            mockServer.takeRequest(500, TimeUnit.MILLISECONDS),
            "An opted-out client must not fire a /flags request nor a \$exception event",
        )

        postHog.close()
        mockServer.shutdown()
    }
}
