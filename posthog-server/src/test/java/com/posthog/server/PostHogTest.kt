package com.posthog.server

import okhttp3.mockwebserver.MockResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

internal class PostHogTest {
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

    @Test
    fun `capture with sendFeatureFlags appends flags to event properties`() {
        val flagsResponse =
            """
            {
                "flags": {
                    "flag1": {
                        "key": "flag1",
                        "enabled": true,
                        "variant": "variant_a",
                        "metadata": { "version": 1, "payload": null, "id": 1 },
                        "reason": { "kind": "condition_match", "condition_match_type": "Test", "condition_index": 0 }
                    },
                    "flag2": {
                        "key": "flag2",
                        "enabled": true,
                        "variant": null,
                        "metadata": { "version": 1, "payload": null, "id": 2 },
                        "reason": { "kind": "condition_match", "condition_match_type": "Test", "condition_index": 0 }
                    },
                    "flag3": {
                        "key": "flag3",
                        "enabled": false,
                        "variant": null,
                        "metadata": { "version": 1, "payload": null, "id": 3 },
                        "reason": { "kind": "condition_match", "condition_match_type": "Test", "condition_index": 0 }
                    }
                }
            }
            """.trimIndent()

        val mockServer =
            createMockHttp(
                jsonResponse(flagsResponse),
                MockResponse().setResponseCode(200).setBody("{}"),
            )
        val url = mockServer.url("/")

        val config = PostHogConfig(apiKey = TEST_API_KEY, host = url.toString())
        val postHog = PostHog()
        postHog.setup(config)

        // Capture with sendFeatureFlags
        val sendFeatureFlagOptions =
            PostHogSendFeatureFlagOptions.builder()
                .personProperty("email", "test@example.com")
                .build()

        postHog.capture(
            distinctId = "user123",
            event = "test_event",
            properties = mapOf("prop" to "value"),
            userProperties = null,
            userPropertiesSetOnce = null,
            groups = null,
            timestamp = null,
            sendFeatureFlags = sendFeatureFlagOptions,
        )

        postHog.flush()

        mockServer.takeRequest() // flags request
        val batchRequest = mockServer.takeRequest()

        // Decompress the batch body if gzipped
        val batchBody =
            if (batchRequest.getHeader("Content-Encoding") == "gzip") {
                batchRequest.body.unGzip()
            } else {
                batchRequest.body.readUtf8()
            }

        // Parse the batch request JSON
        val gson = com.google.gson.Gson()

        @Suppress("UNCHECKED_CAST")
        val batchData = gson.fromJson(batchBody, Map::class.java) as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        val batch = batchData["batch"] as List<Map<String, Any>>

        assertEquals(1, batch.size)

        val event = batch[0]
        assertEquals("test_event", event["event"])
        assertEquals("user123", event["distinct_id"])

        @Suppress("UNCHECKED_CAST")
        val properties = event["properties"] as Map<String, Any>
        assertEquals("value", properties["prop"])
        assertEquals("variant_a", properties["\$feature/flag1"])
        assertEquals(true, properties["\$feature/flag2"])
        assertEquals(false, properties["\$feature/flag3"])

        @Suppress("UNCHECKED_CAST")
        val activeFlags = properties["\$active_feature_flags"] as? List<String>
        assertEquals(2, activeFlags?.size)
        assertEquals(true, activeFlags?.contains("flag1"))
        assertEquals(true, activeFlags?.contains("flag2"))

        mockServer.shutdown()
        postHog.close()
    }
}
