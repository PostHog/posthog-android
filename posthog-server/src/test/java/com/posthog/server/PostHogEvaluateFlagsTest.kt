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

@Suppress("DEPRECATION")
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
        // has_experiment absent from the response metadata, so the property is omitted
        assertFalse(props.containsKey("\$feature_flag_has_experiment"))

        postHog.close()
        mockServer.shutdown()
    }

    @Test
    fun `feature_flag_called reports has_experiment true when the response metadata reports it`() {
        val flagsBody =
            """
            {
                "flags": {
                    "a": {
                        "key": "a",
                        "enabled": true,
                        "variant": null,
                        "metadata": { "version": 4, "payload": null, "id": 11, "has_experiment": true },
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
        postHog.flush()

        val requests = drainRequests(mockServer)
        val batch = requests.first { it.path?.contains("/batch") == true }.parseBatch()
        val props = batch.eventProperties("\$feature_flag_called")
        assertEquals(true, props["\$feature_flag_has_experiment"])

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
    fun `evaluateFlags uses request context distinctId when omitted or null`() {
        val cases =
            listOf<Pair<String, (PostHogInterface) -> PostHogFeatureFlagEvaluations>>(
                "omitted distinctId" to { client -> client.evaluateFlags() },
                "null distinctId" to { client -> client.evaluateFlags(null) },
            )

        for ((caseName, evaluateFlags) in cases) {
            val mockServer = MockWebServer()
            mockServer.enqueue(jsonResponse(createFlagsResponse("a", enabled = true)))
            mockServer.start()

            var postHog: PostHogInterface? = null
            try {
                postHog =
                    PostHog.with(
                        PostHogConfig.builder(TEST_API_KEY)
                            .host(mockServer.url("/").toString())
                            .build(),
                    )

                val snapshot =
                    PostHogRequestContext.withContext(PostHogRequestContextData(distinctId = "context-user")) {
                        evaluateFlags(postHog)
                    }

                assertEquals("context-user", snapshot.distinctId, "case: $caseName")
                assertTrue(snapshot.isEnabled("a"), "case: $caseName")

                val request = mockServer.takeRequest(2, TimeUnit.SECONDS)
                assertNotNull(request, "case: $caseName")
                val body = request.body.unGzip()
                assertTrue(
                    body.contains("\"distinct_id\":\"context-user\""),
                    "expected context distinctId in request body for $caseName, got: $body",
                )
            } finally {
                postHog?.close()
                mockServer.shutdown()
            }
        }
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

    @Test
    fun `quotaLimited response propagates feature_flag_error to snapshot events`() {
        val flagsBody = createFlagsResponseWithQuotaLimited(flagKey = "a", enabled = true)
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
        postHog.flush()

        val requests = drainRequests(mockServer)
        val batch = requests.first { it.path?.contains("/batch") == true }.parseBatch()
        val props = batch.eventProperties("\$feature_flag_called")
        assertEquals("quota_limited", props["\$feature_flag_error"])

        postHog.close()
        mockServer.shutdown()
    }

    @Test
    fun `capture preserves user-supplied feature properties over snapshot values`() {
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
            properties =
                mapOf(
                    // user-supplied $feature/a is "user-override" — must win over snapshot's `true`
                    "\$feature/a" to "user-override",
                ),
            flags = snapshot,
        )

        val requests = drainRequests(mockServer)
        val batch = requests.first { it.path?.contains("/batch") == true }.parseBatch()
        val props = batch.eventProperties("purchase")
        assertEquals("user-override", props["\$feature/a"])
        // Other flags from snapshot still attached
        assertEquals(false, props["\$feature/b"])

        postHog.close()
        mockServer.shutdown()
    }

    @Test
    fun `evaluateFlags caches per (distinctId, flagKeys, disableGeoip) tuple`() {
        // First call with flagKeys=[a] — only "a" comes back
        // Second call with flagKeys=[a, b] — must miss the cache and hit /flags again
        val mockServer = MockWebServer()
        mockServer.enqueue(jsonResponse(createMultipleFlagsResponse("a" to true)))
        mockServer.enqueue(jsonResponse(createMultipleFlagsResponse("a" to true, "b" to false)))
        mockServer.start()

        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(mockServer.url("/").toString())
                    .build(),
            )

        val first = postHog.evaluateFlags("user-1", flagKeys = listOf("a"))
        val second = postHog.evaluateFlags("user-1", flagKeys = listOf("a", "b"))

        assertEquals(setOf("a"), first.keys.toSet())
        assertEquals(setOf("a", "b"), second.keys.toSet())

        val requests = drainRequests(mockServer)
        val flagsRequests = requests.filter { it.path?.contains("/flags") == true }
        assertEquals(2, flagsRequests.size, "different flagKeys must miss the cache")

        postHog.close()
        mockServer.shutdown()
    }

    /**
     * Serves flag definitions and `/flags` from one path-routed dispatcher, so the asynchronous
     * local evaluation poller cannot race the request under test for an enqueued response.
     */
    private fun withLocalEvaluation(
        definitions: String,
        flagsResponse: () -> MockResponse = { jsonResponse(createEmptyFlagsResponse()) },
        localEvaluationResponse: () -> MockResponse = { jsonResponse(definitions) },
        block: (PostHogInterface, CountingDispatcher, MockWebServer) -> Unit,
    ) {
        val dispatcher = CountingDispatcher(localEvaluationResponse, flagsResponse)
        val mockServer = MockWebServer()
        mockServer.dispatcher = dispatcher
        mockServer.start()

        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(mockServer.url("/").toString())
                    .personalApiKey("phx_test_personal_api_key")
                    .flushAt(1)
                    .build(),
            )

        try {
            block(postHog, dispatcher, mockServer)
        } finally {
            postHog.close()
            mockServer.shutdown()
        }
    }

    /** One flag local evaluation always resolves, one it can never resolve without an `email`. */
    private fun conclusiveAndGatedDefinitions(): String =
        createLocalEvaluationResponseFrom(
            conclusiveFlagDefinition("conclusive"),
            emailGatedFlagDefinition("gated"),
        )

    @Test
    fun `an inconclusive flag does not discard the flags that resolved locally`() {
        withLocalEvaluation(
            definitions = conclusiveAndGatedDefinitions(),
            // The server disagrees about `conclusive`, and is the only source for `gated`.
            flagsResponse = { jsonResponse(createMultipleFlagsResponse("conclusive" to false, "gated" to true)) },
        ) { postHog, dispatcher, mockServer ->
            val snapshot = postHog.evaluateFlags("user-1")

            assertTrue(snapshot.isEnabled("conclusive"), "the local value must win over the server's")
            assertTrue(snapshot.isEnabled("gated"), "the unresolvable key must be filled from /flags")
            postHog.flush()

            assertEquals(1, dispatcher.flagsCalls.get(), "one request, for the unresolved key only")

            val flagCalled = drainRequests(mockServer).featureFlagCalledEvents().toMap()
            val conclusive = assertNotNull(flagCalled["conclusive"])
            assertEquals(true, conclusive["locally_evaluated"])
            assertEquals("Evaluated locally", conclusive["\$feature_flag_reason"])
            val gated = assertNotNull(flagCalled["gated"])
            assertFalse(gated.containsKey("locally_evaluated"), "a remote-filled key is not locally evaluated")
        }
    }

    @Test
    fun `an error thrown while evaluating one flag falls back for that flag instead of crashing`() {
        withLocalEvaluation(
            definitions =
                createLocalEvaluationResponseFrom(
                    conclusiveFlagDefinition("conclusive"),
                    throwingFlagDefinition("broken"),
                ),
            // The server disagrees about `conclusive`, and is the only source for `broken`.
            flagsResponse = { jsonResponse(createMultipleFlagsResponse("conclusive" to false, "broken" to true)) },
        ) { postHog, dispatcher, mockServer ->
            // Must not propagate the evaluator's NullPointerException.
            val snapshot = postHog.evaluateFlags("user-1")

            assertTrue(snapshot.isEnabled("conclusive"), "the local value must win over the server's")
            assertTrue(snapshot.isEnabled("broken"), "the throwing key must be filled from /flags")
            postHog.flush()

            assertEquals(1, dispatcher.flagsCalls.get(), "one request, for the throwing key only")

            val flagCalled = drainRequests(mockServer).featureFlagCalledEvents().toMap()
            assertEquals(true, assertNotNull(flagCalled["conclusive"])["locally_evaluated"])
            assertFalse(
                assertNotNull(flagCalled["broken"]).containsKey("locally_evaluated"),
                "a remote-filled key is not locally evaluated",
            )
        }
    }

    @Test
    fun `a requested undefined key is filled by a request an unresolved flag already forced`() {
        withLocalEvaluation(
            definitions = conclusiveAndGatedDefinitions(),
            flagsResponse = { jsonResponse(createMultipleFlagsResponse("gated" to true, "brand-new-flag" to true)) },
        ) { postHog, dispatcher, _ ->
            val snapshot = postHog.evaluateFlags("user-1", flagKeys = listOf("gated", "brand-new-flag"))

            assertEquals(1, dispatcher.flagsCalls.get(), "the inconclusive key forces the request")
            assertTrue(snapshot.isEnabled("gated"))
            assertTrue(
                snapshot.isEnabled("brand-new-flag"),
                "the undefined key rides the request the inconclusive key forced",
            )
        }
    }

    @Test
    fun `flagKeys scopes local evaluation so an unrequested inconclusive flag forces no request`() {
        withLocalEvaluation(
            definitions = conclusiveAndGatedDefinitions(),
            flagsResponse = { jsonResponse(createMultipleFlagsResponse("conclusive" to false, "gated" to true)) },
        ) { postHog, dispatcher, mockServer ->
            val snapshot = postHog.evaluateFlags("user-1", flagKeys = listOf("conclusive"))

            assertEquals(setOf("conclusive"), snapshot.keys.toSet())
            assertTrue(snapshot.isEnabled("conclusive"))
            postHog.flush()

            assertEquals(0, dispatcher.flagsCalls.get(), "every requested key resolved locally")

            val flagCalled = drainRequests(mockServer).featureFlagCalledEvents().toMap()
            assertEquals(true, assertNotNull(flagCalled["conclusive"])["locally_evaluated"])
        }
    }

    @Test
    fun `a flags outage leaves locally-resolved flags on and is not retried within the cache window`() {
        withLocalEvaluation(
            definitions = conclusiveAndGatedDefinitions(),
            flagsResponse = { MockResponse().setResponseCode(503).setBody("unavailable") },
        ) { postHog, dispatcher, mockServer ->
            val first = postHog.evaluateFlags("user-1")
            assertTrue(first.isEnabled("conclusive"), "definitions in memory still say this flag is on")

            val second = postHog.evaluateFlags("user-1")
            assertTrue(second.isEnabled("conclusive"))
            postHog.flush()

            assertEquals(1, dispatcher.flagsCalls.get(), "the cached failure must not be re-requested")

            val flagCalled = drainRequests(mockServer).featureFlagCalledEvents().toMap()
            assertEquals("api_error_503", assertNotNull(flagCalled["conclusive"])["\$feature_flag_error"])
        }
    }

    @Test
    fun `a requested key with no local definition is absent and asks the server for nothing`() {
        val fixtures =
            listOf(
                "all definitions conclusive" to createLocalEvaluationResponseFrom(conclusiveFlagDefinition("conclusive")),
                "one definition inconclusive" to conclusiveAndGatedDefinitions(),
            )

        for ((caseName, definitions) in fixtures) {
            withLocalEvaluation(
                definitions = definitions,
                // The server could answer for this key — the point is that we never ask.
                flagsResponse = { jsonResponse(createMultipleFlagsResponse("brand-new-flag" to true)) },
            ) { postHog, dispatcher, _ ->
                val snapshot = postHog.evaluateFlags("user-1", flagKeys = listOf("brand-new-flag"))

                assertTrue(snapshot.keys.isEmpty(), "case: $caseName")
                assertFalse(snapshot.isEnabled("brand-new-flag"), "case: $caseName")
                assertEquals(0, dispatcher.flagsCalls.get(), "case: $caseName")
            }
        }
    }

    @Test
    fun `onlyEvaluateLocally ignores a cached remote failure and still evaluates locally`() {
        withLocalEvaluation(
            definitions = conclusiveAndGatedDefinitions(),
            flagsResponse = { MockResponse().setResponseCode(503).setBody("unavailable") },
        ) { postHog, dispatcher, _ ->
            // Cache a failure for this identity, as any other call site would.
            postHog.evaluateFlags("user-1")

            val localOnly = postHog.evaluateFlags("user-1", onlyEvaluateLocally = true)

            assertTrue(localOnly.keys.contains("conclusive"))
            assertTrue(localOnly.isEnabled("conclusive"))
            assertEquals(1, dispatcher.flagsCalls.get())
        }
    }

    @Test
    fun `onlyEvaluateLocally never serves cached remote values`() {
        withLocalEvaluation(
            definitions = conclusiveAndGatedDefinitions(),
            flagsResponse = {
                jsonResponse(
                    createMultipleFlagsResponse("conclusive" to false, "gated" to true, "remote-only" to true),
                )
            },
        ) { postHog, dispatcher, _ ->
            val remote = postHog.evaluateFlags("user-1")
            assertTrue(remote.keys.contains("remote-only"), "the cache now holds keys local evaluation cannot produce")

            val localOnly = postHog.evaluateFlags("user-1", onlyEvaluateLocally = true)

            assertEquals(
                setOf("conclusive"),
                localOnly.keys.toSet(),
                "a local-only snapshot holds exactly what local evaluation resolved",
            )
            assertTrue(localOnly.isEnabled("conclusive"))
            assertEquals(1, dispatcher.flagsCalls.get())
        }
    }

    @Test
    fun `repeat calls in the cache window keep the merged values`() {
        withLocalEvaluation(
            definitions = conclusiveAndGatedDefinitions(),
            flagsResponse = { jsonResponse(createMultipleFlagsResponse("conclusive" to false, "gated" to true)) },
        ) { postHog, dispatcher, mockServer ->
            val first = postHog.evaluateFlags("user-1")
            first.isEnabled("conclusive")
            first.isEnabled("gated")

            val second = postHog.evaluateFlags("user-1")
            assertTrue(second.isEnabled("conclusive"), "call #2 must not fall back to the server's value")
            assertTrue(second.isEnabled("gated"))
            postHog.flush()

            assertEquals(1, dispatcher.flagsCalls.get())

            val events = drainRequests(mockServer).featureFlagCalledEvents()
            assertEquals(1, events.count { it.first == "conclusive" }, "a value flip would emit a second event")
            assertEquals(1, events.count { it.first == "gated" })
        }
    }

    @Test
    fun `the deprecated appendFeatureFlags path discards local results wholesale`() {
        withLocalEvaluation(
            definitions = conclusiveAndGatedDefinitions(),
            flagsResponse = { jsonResponse(createMultipleFlagsResponse("conclusive" to false, "gated" to true)) },
        ) { postHog, dispatcher, mockServer ->
            postHog.capture(distinctId = "user-1", event = "page_view", appendFeatureFlags = true)
            postHog.flush()

            assertEquals(1, dispatcher.flagsCalls.get())

            val requests = drainRequests(mockServer)
            val batch = requests.first { it.path?.contains("/batch") == true }.parseBatch()
            val props = batch.eventProperties("page_view")
            // The local definitions say `conclusive` is 100% on; the legacy path takes the
            // server's answer for the whole batch regardless.
            assertEquals(false, props["\$feature/conclusive"])
        }
    }

    @Test
    fun `flagKeys scoping leaves flag dependencies resolvable`() {
        val dependentFlag =
            """
            {
                "id": 3,
                "name": "dependent-flag",
                "key": "dependent-flag",
                "active": true,
                "filters": {
                    "groups": [
                        {
                            "properties": [
                                {
                                    "key": "base-flag",
                                    "type": "flag",
                                    "value": true,
                                    "operator": "flag_evaluates_to",
                                    "dependency_chain": ["base-flag"]
                                }
                            ],
                            "rollout_percentage": 100
                        }
                    ]
                },
                "version": 1
            }
            """.trimIndent()

        withLocalEvaluation(
            definitions =
                createLocalEvaluationResponseFrom(
                    conclusiveFlagDefinition("base-flag"),
                    dependentFlag,
                ),
            flagsResponse = { jsonResponse(createMultipleFlagsResponse("dependent-flag" to false)) },
        ) { postHog, dispatcher, _ ->
            val snapshot = postHog.evaluateFlags("user-1", flagKeys = listOf("dependent-flag"))

            // Scoping filters the evaluation loop, not the definition map, so `base-flag` is still
            // reachable as a dependency even though it was not requested.
            assertEquals(setOf("dependent-flag"), snapshot.keys.toSet())
            assertTrue(snapshot.isEnabled("dependent-flag"))
            assertEquals(0, dispatcher.flagsCalls.get())
        }
    }

    /**
     * A 100%-rollout flag aggregated on group type 0, mapped to `organization`, alongside any extra
     * definitions. Needs its own `group_type_mapping`: with an unmapped index the flag is
     * inconclusive rather than group-resolved.
     */
    private fun groupFlagDefinitions(vararg extraFlagDefinitions: String): String {
        val groupFlag =
            """
            {
                "id": 4,
                "name": "group-flag",
                "key": "group-flag",
                "active": true,
                "filters": {
                    "aggregation_group_type_index": 0,
                    "groups": [
                        { "properties": [], "rollout_percentage": 100 }
                    ]
                },
                "version": 1
            }
            """.trimIndent()

        return """
            {
                "flags": [ ${(listOf(groupFlag) + extraFlagDefinitions).joinToString(",")} ],
                "group_type_mapping": { "0": "organization" },
                "cohorts": {}
            }
            """.trimIndent()
    }

    @Test
    fun `a group flag with no groups supplied resolves locally to false and wins over the server`() {
        // `computeFlagLocally` answers `false` rather than throwing when a group-aggregated
        // flag's group key is missing, so the flag counts as locally resolved and the merge keeps
        // that `false` over the server's `true`. Callers gating on group flags must pass `groups`.
        withLocalEvaluation(
            definitions = groupFlagDefinitions(emailGatedFlagDefinition("gated")),
            flagsResponse = { jsonResponse(createMultipleFlagsResponse("group-flag" to true, "gated" to true)) },
        ) { postHog, dispatcher, _ ->
            val snapshot = postHog.evaluateFlags("user-1")

            assertFalse(snapshot.isEnabled("group-flag"), "the local false wins over the server's true")
            assertTrue(snapshot.isEnabled("gated"), "the inconclusive sibling still comes from /flags")
            assertEquals(1, dispatcher.flagsCalls.get())
        }
    }

    @Test
    fun `a group flag resolves locally from the supplied groups`() {
        withLocalEvaluation(
            definitions = groupFlagDefinitions(),
            flagsResponse = { jsonResponse(createMultipleFlagsResponse("group-flag" to false)) },
        ) { postHog, dispatcher, _ ->
            val snapshot = postHog.evaluateFlags("user-1", groups = mapOf("organization" to "org-1"))

            assertTrue(snapshot.isEnabled("group-flag"))
            assertEquals(0, dispatcher.flagsCalls.get())
        }
    }

    @Test
    fun `loaded but empty definitions return an empty snapshot without a request`() {
        withLocalEvaluation(
            definitions = createLocalEvaluationResponseFrom(),
            flagsResponse = { jsonResponse(createMultipleFlagsResponse("remote-only" to true)) },
        ) { postHog, dispatcher, _ ->
            val snapshot = postHog.evaluateFlags("user-1")

            assertTrue(snapshot.keys.isEmpty())
            assertEquals(0, dispatcher.flagsCalls.get(), "a project with no flags must not be billed for a request")
        }
    }

    @Test
    fun `capture with appendFeatureFlags=true still attaches feature properties (deprecated path keeps working)`() {
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

        // The runtime DEPRECATION log fires through the core PostHogConfig logger; we don't have a
        // public hook to swap that logger in the server config, so we assert behavior instead:
        // the deprecated path still works end-to-end and attaches the same properties.
        postHog.capture(
            distinctId = "user-1",
            event = "page_view",
            appendFeatureFlags = true,
        )

        val requests = drainRequests(mockServer)
        val batch = requests.first { it.path?.contains("/batch") == true }.parseBatch()
        val props = batch.eventProperties("page_view")
        assertEquals(true, props["\$feature/a"])

        postHog.close()
        mockServer.shutdown()
    }
}
