package com.posthog.server

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogEvaluateFlagsTest {
    private fun drainRequests(server: MockWebServer): List<RecordedRequest> {
        val requests = mutableListOf<RecordedRequest>()
        var request = server.takeRequest(2, TimeUnit.SECONDS)
        while (request != null) {
            requests.add(request)
            request = server.takeRequest(100, TimeUnit.MILLISECONDS)
        }
        return requests
    }

    @Test
    fun `evaluateFlags returns a snapshot and makes exactly one flags request`() {
        val mockServer = MockWebServer()
        mockServer.enqueue(jsonResponse(createMultipleFlagsResponse("a" to true, "b" to false)))
        mockServer.start()

        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(mockServer.url("/").toString())
                    .flushAt(1)
                    .build(),
            )

        val snapshot = postHog.evaluateFlags("user-1")

        assertEquals(setOf("a", "b"), snapshot.keys.toSet())
        assertTrue(snapshot.isEnabled("a"))
        assertFalse(snapshot.isEnabled("b"))

        val requests = drainRequests(mockServer)
        val flagsRequests = requests.filter { it.path?.contains("/flags") == true }
        assertEquals(1, flagsRequests.size, "expected exactly one /flags request")

        postHog.close()
        mockServer.shutdown()
    }

    @Test
    fun `no feature_flag_called events fire until a flag is accessed`() {
        val mockServer = MockWebServer()
        mockServer.enqueue(jsonResponse(createFlagsResponse("a", enabled = true)))
        mockServer.enqueue(MockResponse().setResponseCode(200))
        mockServer.start()

        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(mockServer.url("/").toString())
                    .flushAt(1)
                    .build(),
            )

        // build the snapshot but don't access any flag — flush a different event
        postHog.evaluateFlags("user-1")
        postHog.capture("user-1", "page_view")

        val requests = drainRequests(mockServer)
        val batchRequests = requests.filter { it.path?.contains("/batch") == true }
        assertEquals(1, batchRequests.size)

        val events = batchRequests.single().parseBatch().batch.map { it.get("event").asString }
        assertFalse(events.contains("\$feature_flag_called"), "no \$feature_flag_called expected before access")

        postHog.close()
        mockServer.shutdown()
    }

    @Test
    fun `isEnabled fires feature_flag_called once with full metadata, deduped on second access`() {
        val flagsBody =
            """
            {
                "flags": {
                    "a": {
                        "key": "a",
                        "enabled": true,
                        "variant": null,
                        "metadata": { "version": 4, "payload": null, "id": 11 },
                        "reason": { "code": "condition_match", "description": "Matched", "condition_index": 0 }
                    }
                },
                "requestId": "req-fixture"
            }
            """.trimIndent()
        val mockServer = MockWebServer()
        mockServer.enqueue(jsonResponse(flagsBody))
        mockServer.enqueue(MockResponse().setResponseCode(200))
        mockServer.start()

        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(mockServer.url("/").toString())
                    .flushAt(1)
                    .build(),
            )

        val snapshot = postHog.evaluateFlags("user-1")
        snapshot.isEnabled("a")
        snapshot.isEnabled("a")
        postHog.flush()

        val requests = drainRequests(mockServer)
        val batch = requests.first { it.path?.contains("/batch") == true }.parseBatch()
        val flagCalledEvents = batch.batch.filter { it.get("event").asString == "\$feature_flag_called" }
        assertEquals(1, flagCalledEvents.size, "second access must dedup")

        val props = batch.eventProperties("\$feature_flag_called")
        assertEquals("a", props["\$feature_flag"])
        assertEquals(true, props["\$feature_flag_response"])
        assertEquals(11.0, props["\$feature_flag_id"]) // gson deserializes ints as doubles
        assertEquals(4.0, props["\$feature_flag_version"])
        assertEquals("Matched", props["\$feature_flag_reason"])
        assertEquals("req-fixture", props["\$feature_flag_request_id"])

        postHog.close()
        mockServer.shutdown()
    }

    @Test
    fun `getFlagPayload does not fire a feature_flag_called event`() {
        val mockServer = MockWebServer()
        mockServer.enqueue(jsonResponse(createFlagsResponse("a", enabled = true, payload = "p")))
        mockServer.enqueue(MockResponse().setResponseCode(200))
        mockServer.start()

        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(mockServer.url("/").toString())
                    .flushAt(1)
                    .build(),
            )

        val snapshot = postHog.evaluateFlags("user-1")
        snapshot.getFlagPayload("a")
        postHog.capture("user-1", "page_view")

        val requests = drainRequests(mockServer)
        val batch = requests.first { it.path?.contains("/batch") == true }.parseBatch()
        val events = batch.batch.map { it.get("event").asString }
        assertFalse(events.contains("\$feature_flag_called"), "payload reads must not emit events")

        postHog.close()
        mockServer.shutdown()
    }

    @Test
    fun `capture with flags snapshot attaches feature properties without a second flags request`() {
        val mockServer = MockWebServer()
        mockServer.enqueue(jsonResponse(createMultipleFlagsResponse("a" to true, "b" to false)))
        mockServer.enqueue(MockResponse().setResponseCode(200))
        mockServer.start()

        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(mockServer.url("/").toString())
                    .flushAt(1)
                    .build(),
            )

        val snapshot = postHog.evaluateFlags("user-1")
        postHog.capture(
            distinctId = "user-1",
            event = "purchase",
            properties = mapOf("amount" to 1),
            flags = snapshot,
        )

        val requests = drainRequests(mockServer)
        val flagsRequests = requests.filter { it.path?.contains("/flags") == true }
        assertEquals(1, flagsRequests.size, "capture(flags=…) must NOT issue another /flags call")

        val batch = requests.first { it.path?.contains("/batch") == true }.parseBatch()
        val props = batch.eventProperties("purchase")
        assertEquals(true, props["\$feature/a"])
        assertEquals(false, props["\$feature/b"])

        @Suppress("UNCHECKED_CAST")
        val active = props["\$active_feature_flags"] as? List<String>
        assertNotNull(active)
        assertTrue(active.contains("a"))
        assertFalse(active.contains("b"))

        postHog.close()
        mockServer.shutdown()
    }

    @Test
    fun `flagKeys is forwarded to the flags request body`() {
        val mockServer = MockWebServer()
        mockServer.enqueue(jsonResponse(createFlagsResponse("a", enabled = true)))
        mockServer.start()

        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(mockServer.url("/").toString())
                    .build(),
            )

        postHog.evaluateFlags("user-1", flagKeys = listOf("a", "b"))

        val request = mockServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(request)
        val body = request.body.unGzip()
        assertTrue(
            body.contains("\"flag_keys_to_evaluate\""),
            "expected flag_keys_to_evaluate in request body, got: $body",
        )
        assertTrue(body.contains("\"a\""))
        assertTrue(body.contains("\"b\""))

        postHog.close()
        mockServer.shutdown()
    }

    @Test
    fun `evaluateFlags with blank distinctId returns an empty snapshot and fires no events on access`() {
        val mockServer = MockWebServer()
        mockServer.start()

        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(mockServer.url("/").toString())
                    .flushAt(1)
                    .build(),
            )

        val snapshot = postHog.evaluateFlags("")
        assertTrue(snapshot.keys.isEmpty())
        assertNull(snapshot.distinctId)

        snapshot.isEnabled("anything")
        postHog.capture("u", "page_view")

        val requests = drainRequests(mockServer)
        val flagsRequests = requests.filter { it.path?.contains("/flags") == true }
        assertEquals(0, flagsRequests.size, "blank distinctId must short-circuit /flags")
        val batch = requests.firstOrNull { it.path?.contains("/batch") == true }?.parseBatch()
        if (batch != null) {
            val events = batch.batch.map { it.get("event").asString }
            assertFalse(events.contains("\$feature_flag_called"))
        }

        postHog.close()
        mockServer.shutdown()
    }

    @Test
    fun `local evaluation snapshot tags events with locally_evaluated and reason`() {
        val localEvalResponse = createLocalEvaluationResponse("local-flag")
        val mockServer = MockWebServer()
        mockServer.enqueue(jsonResponse(localEvalResponse))
        mockServer.enqueue(MockResponse().setResponseCode(200))
        mockServer.start()

        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(mockServer.url("/").toString())
                    .personalApiKey("phx_test_personal_api_key")
                    .flushAt(1)
                    .build(),
            )

        val snapshot = postHog.evaluateFlags("user-1")
        snapshot.isEnabled("local-flag")
        postHog.flush()

        val requests = drainRequests(mockServer)
        val batch = requests.first { it.path?.contains("/batch") == true }.parseBatch()
        val props = batch.eventProperties("\$feature_flag_called")
        assertEquals("Evaluated locally", props["\$feature_flag_reason"])
        assertEquals(true, props["locally_evaluated"])
        assertFalse(
            requests.any { it.path?.contains("/flags") == true && !it.path!!.contains("local_evaluation") },
            "local evaluation should not hit /flags",
        )

        postHog.close()
        mockServer.shutdown()
    }
}
