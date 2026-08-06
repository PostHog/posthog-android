package com.posthog.server

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The customer ticket, end to end: one inconclusive flag definition used to disable local
 * evaluation for the whole project when using evaluateFlags(). Each test is one row of the
 * reproduction table, now asserting the post-fix behaviour.
 */
internal class TicketReproTest {
    private val taxieAccess = conclusiveFlagDefinition("taxie-access")

    private val anotherTeamFlag = emailGatedFlagDefinition("another-team-flag")

    private fun localEvalBody(vararg flags: String): String = createLocalEvaluationResponseFrom(*flags)

    private fun okFlags(): MockResponse =
        MockResponse()
            .setBody(createMultipleFlagsResponse("taxie-access" to true, "another-team-flag" to false))
            .setHeader("Content-Type", "application/json")

    private fun runScenario(
        localEvalBody: String,
        flagsResponse: () -> MockResponse = ::okFlags,
        block: (PostHogInterface, String) -> Boolean,
    ): Pair<Int, List<Boolean>> {
        val dispatcher = CountingDispatcher({ jsonResponse(localEvalBody) }, flagsResponse)
        val server = MockWebServer()
        server.dispatcher = dispatcher
        server.start()

        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(server.url("/").toString())
                    .personalApiKey("phx_test_personal_api_key")
                    .flushAt(1000)
                    .build(),
            )

        val values = mutableListOf<Boolean>()
        try {
            for (i in 1..5) {
                values.add(block(postHog, "user-$i"))
            }
        } finally {
            postHog.close()
            server.shutdown()
        }
        return dispatcher.flagsCalls.get() to values
    }

    @Test
    fun `baseline - only taxie-access defined - zero flags calls`() {
        val (calls, values) =
            runScenario(localEvalBody(taxieAccess)) { ph, id ->
                ph.evaluateFlags(id).isEnabled("taxie-access")
            }
        println("REPRO baseline: /flags calls = $calls, values = $values")
        assertEquals(0, calls)
        assertTrue(values.all { it })
    }

    @Test
    fun `regression - another-team-flag also defined - one flags call per distinct id`() {
        val (calls, values) =
            runScenario(localEvalBody(taxieAccess, anotherTeamFlag)) { ph, id ->
                ph.evaluateFlags(id).isEnabled("taxie-access")
            }
        println("REPRO with foreign flag: /flags calls = $calls, values = $values")
        assertEquals(5, calls)
        assertTrue(values.all { it })
    }

    @Test
    fun `flagKeys scopes local evaluation so a foreign flag cannot force a request`() {
        // The reported bug: the foreign flag is inconclusive, but the caller never asked for it.
        val (calls, values) =
            runScenario(localEvalBody(taxieAccess, anotherTeamFlag)) { ph, id ->
                ph.evaluateFlags(id, flagKeys = listOf("taxie-access")).isEnabled("taxie-access")
            }
        println("REPRO with flagKeys: /flags calls = $calls, values = $values")
        assertEquals(0, calls)
        assertTrue(values.all { it })
    }

    @Test
    fun `two-pass workaround yields zero flags calls`() {
        val (calls, values) =
            runScenario(localEvalBody(taxieAccess, anotherTeamFlag)) { ph, id ->
                val local = ph.evaluateFlags(id, onlyEvaluateLocally = true)
                if (local.keys.contains("taxie-access")) {
                    local.isEnabled("taxie-access")
                } else {
                    ph.evaluateFlags(id).isEnabled("taxie-access")
                }
            }
        println("REPRO two-pass: /flags calls = $calls, values = $values")
        assertEquals(0, calls)
        assertTrue(values.all { it })
    }

    @Test
    fun `flags outage leaves a locally-resolvable flag on`() {
        val (calls, values) =
            runScenario(
                localEvalBody(taxieAccess, anotherTeamFlag),
                flagsResponse = { MockResponse().setResponseCode(503).setBody("nope") },
            ) { ph, id ->
                ph.evaluateFlags(id).isEnabled("taxie-access")
            }
        println("REPRO outage: /flags calls = $calls, values = $values")
        // Still one attempt per identity — the request is needed for the inconclusive flag.
        assertEquals(5, calls)
        // The definitions in memory say taxie-access is 100% on, and PostHog being unreachable
        // no longer changes that.
        assertTrue(values.all { it })
    }

    @Test
    fun `locally-computable value wins over the remote answer`() {
        // Local definitions say taxie-access is 100% on; the server says it is off.
        val (calls, values) =
            runScenario(
                localEvalBody(taxieAccess, anotherTeamFlag),
                flagsResponse = {
                    MockResponse()
                        .setBody(createMultipleFlagsResponse("taxie-access" to false, "another-team-flag" to false))
                        .setHeader("Content-Type", "application/json")
                },
            ) { ph, id ->
                ph.evaluateFlags(id).isEnabled("taxie-access")
            }
        println("REPRO local-wins: /flags calls = $calls, values = $values")
        assertEquals(5, calls)
        assertTrue(values.all { it })
    }

    @Test
    fun `two-pass with flagKeys on both passes`() {
        val (calls, values) =
            runScenario(localEvalBody(taxieAccess, anotherTeamFlag)) { ph, id ->
                val keys = listOf("taxie-access")
                val local = ph.evaluateFlags(id, flagKeys = keys, onlyEvaluateLocally = true)
                if (local.keys.contains("taxie-access")) {
                    local.isEnabled("taxie-access")
                } else {
                    ph.evaluateFlags(id, flagKeys = keys).isEnabled("taxie-access")
                }
            }
        println("REPRO two-pass+flagKeys: /flags calls = $calls, values = $values")
        assertEquals(0, calls)
        assertTrue(values.all { it })
    }

    @Test
    fun `supplying the foreign flag's property restores local evaluation`() {
        val (calls, values) =
            runScenario(localEvalBody(taxieAccess, anotherTeamFlag)) { ph, id ->
                ph.evaluateFlags(
                    id,
                    personProperties = mapOf("email" to "someone@example.com"),
                ).isEnabled("taxie-access")
            }
        println("REPRO with email supplied: /flags calls = $calls, values = $values")
        assertEquals(0, calls)
        assertTrue(values.all { it })
    }

    @Test
    fun `flagKeys naming a key with no local definition returns false without asking the server`() {
        // Only taxie-access is defined locally. Ask for a key the poller has never seen.
        val dispatcher = CountingDispatcher({ jsonResponse(localEvalBody(taxieAccess)) }, ::okFlags)
        val server = MockWebServer()
        server.dispatcher = dispatcher
        server.start()
        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(server.url("/").toString())
                    .personalApiKey("phx_test_personal_api_key")
                    .flushAt(1000)
                    .build(),
            )

        val snap = postHog.evaluateFlags("user-1", flagKeys = listOf("brand-new-flag"))
        println(
            "REPRO undefined-key: keys=${snap.keys}, isEnabled=${snap.isEnabled("brand-new-flag")}, " +
                "/flags calls = ${dispatcher.flagsCalls.get()}",
        )
        assertEquals(0, dispatcher.flagsCalls.get())
        assertTrue(snap.keys.isEmpty())
        assertFalse(snap.isEnabled("brand-new-flag"))

        postHog.close()
        server.shutdown()
    }

    @Test
    fun `a cached remote failure no longer disarms the onlyEvaluateLocally pass`() {
        val dispatcher =
            CountingDispatcher({ jsonResponse(localEvalBody(taxieAccess, anotherTeamFlag)) }) {
                MockResponse().setResponseCode(503).setBody("nope")
            }
        val server = MockWebServer()
        server.dispatcher = dispatcher
        server.start()
        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(server.url("/").toString())
                    .personalApiKey("phx_test_personal_api_key")
                    .flushAt(1000)
                    .build(),
            )

        // Some other call site touches this identity first during the outage.
        postHog.evaluateFlags("user-1").isEnabled("taxie-access")

        // Now run the two-pass workaround for the same identity.
        val pass1 = postHog.evaluateFlags("user-1", onlyEvaluateLocally = true)
        val enabled =
            if (pass1.keys.contains("taxie-access")) {
                pass1.isEnabled("taxie-access")
            } else {
                postHog.evaluateFlags("user-1").isEnabled("taxie-access")
            }
        println(
            "REPRO local-only-after-failure: pass1 keys=${pass1.keys}, enabled=$enabled, " +
                "/flags calls = ${dispatcher.flagsCalls.get()}",
        )
        // Pass 1 evaluates locally instead of serving the cached failure, so the trivially
        // resolvable flag is present and true — and no second request is made.
        assertEquals(1, dispatcher.flagsCalls.get())
        assertTrue(pass1.keys.contains("taxie-access"))
        assertTrue(enabled)

        postHog.close()
        server.shutdown()
    }

    @Test
    fun `an undefined requested key is absent whether or not another flag is inconclusive`() {
        // Same fixture as the customer's project: taxie-access + inconclusive another-team-flag.
        // Previously the batch abort sent this call remote, which resolved the unknown key by
        // accident. Scoped local evaluation now skips both defined flags, so the answer matches
        // the all-conclusive fixture above: absent, no request. This is Python/Node/Go parity.
        val dispatcher =
            CountingDispatcher({ jsonResponse(localEvalBody(taxieAccess, anotherTeamFlag)) }) {
                MockResponse()
                    .setBody(createMultipleFlagsResponse("brand-new-flag" to true))
                    .setHeader("Content-Type", "application/json")
            }
        val server = MockWebServer()
        server.dispatcher = dispatcher
        server.start()
        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_API_KEY)
                    .host(server.url("/").toString())
                    .personalApiKey("phx_test_personal_api_key")
                    .flushAt(1000)
                    .build(),
            )

        val snap = postHog.evaluateFlags("user-1", flagKeys = listOf("brand-new-flag"))
        println(
            "REPRO undefined-key-with-foreign: keys=${snap.keys}, " +
                "isEnabled=${snap.isEnabled("brand-new-flag")}, /flags calls = ${dispatcher.flagsCalls.get()}",
        )
        assertEquals(0, dispatcher.flagsCalls.get())
        assertTrue(snap.keys.isEmpty())
        assertFalse(snap.isEnabled("brand-new-flag"))

        postHog.close()
        server.shutdown()
    }

    @Test
    fun `deprecated per-flag path yields zero flags calls`() {
        val (calls, values) =
            runScenario(localEvalBody(taxieAccess, anotherTeamFlag)) { ph, id ->
                @Suppress("DEPRECATION")
                ph.isFeatureEnabled(id, "taxie-access")
            }
        println("REPRO per-flag getFeatureFlag: /flags calls = $calls, values = $values")
        assertEquals(0, calls)
        assertTrue(values.all { it })
    }
}
