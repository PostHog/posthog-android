package com.posthog.internal.replay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class PostHogTriggerGroupsEvaluatorTest {
    private val evaluator = PostHogTriggerGroupsEvaluator()

    private fun configure(vararg groups: Map<String, Any?>) {
        val config =
            parseTriggerGroupsConfig(
                mapOf(
                    "version" to 2,
                    "triggerGroups" to groups.toList(),
                ),
            )
        assertTrue(config != null)
        evaluator.onConfig(config.groups)
    }

    private fun group(
        id: String,
        sampleRate: Any? = 1.0,
        minDurationMs: Any? = null,
        conditions: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> =
        mapOf(
            "id" to id,
            "name" to id,
            "sampleRate" to sampleRate,
            "minDurationMs" to minDurationMs,
            "conditions" to conditions,
        )

    private fun eventLeg(vararg events: Map<String, Any?>) = mapOf("events" to events.toList())

    private fun urlLeg(pattern: String) = mapOf("urls" to listOf(mapOf("url" to pattern, "matching" to "regex")))

    private fun flagLeg(flag: Any) = mapOf("flag" to flag)

    private fun onEvent(
        sessionId: String = "session-1",
        event: String,
        properties: Map<String, Any?>? = null,
    ): Boolean = evaluator.onEvent(sessionId, event, properties, null)

    private fun screen(
        sessionId: String = "session-1",
        name: String,
    ): Boolean =
        onEvent(
            sessionId = sessionId,
            event = "\$screen",
            properties = mapOf("\$screen_name" to name),
        )

    private fun decide(
        sessionId: String = "session-1",
        flags: Map<String, Any?>? = emptyMap(),
    ) = evaluator.evaluate(sessionId, flags, null)

    @Test
    fun `any combines activated legs and ignores disabled legs`() {
        configure(
            group(
                id = "g1",
                conditions =
                    mapOf("matchType" to "any", "events" to listOf(mapOf("name" to "purchase"))) + urlLeg("^/checkout"),
            ),
        )
        // no flag configured: only event and url legs, both pending
        var decision = decide()
        assertFalse(decision.shouldRecord)
        assertTrue(decision.hasPendingGroups)

        assertTrue(screen(name = "/checkout"))
        decision = decide()
        assertTrue(decision.shouldRecord)
        assertFalse(decision.hasPendingGroups)
        assertEquals(listOf("g1"), decision.matchedGroups.map { it.id })
    }

    @Test
    fun `all drops disabled legs and requires every configured leg`() {
        configure(
            group(
                id = "g1",
                conditions =
                    mapOf(
                        "matchType" to "all",
                        "events" to listOf(mapOf("name" to "purchase")),
                        "flag" to mapOf("flag" to "replay-flag", "variant" to "beta"),
                    ),
            ),
        )

        // flag leg activated, event leg pending -> pending
        var decision = decide(flags = mapOf("replay-flag" to "beta"))
        assertFalse(decision.shouldRecord)
        assertTrue(decision.hasPendingGroups)

        // event leg activated too -> activated
        assertTrue(onEvent(event = "purchase"))
        decision = decide(flags = mapOf("replay-flag" to "beta"))
        assertTrue(decision.shouldRecord)
    }

    @Test
    fun `all with a single configured leg follows that leg`() {
        configure(group(id = "g1", conditions = mapOf("matchType" to "all", "events" to listOf(mapOf("name" to "purchase")))))

        var decision = decide()
        assertTrue(decision.hasPendingGroups)
        assertTrue(onEvent(event = "purchase"))
        decision = decide()
        assertTrue(decision.shouldRecord)
    }

    @Test
    fun `a leg configured but unmatched stays pending under any`() {
        configure(group(id = "g1", conditions = mapOf("matchType" to "any") + flagLeg("replay-flag")))

        val decision = decide(flags = mapOf("replay-flag" to false))
        assertFalse(decision.shouldRecord)
        assertTrue(decision.hasPendingGroups)
    }

    @Test
    fun `empty conditions activate immediately`() {
        configure(group(id = "g1", conditions = mapOf("properties" to listOf(mapOf("key" to "region", "value" to "EU")))))

        val decision = decide()
        assertTrue(decision.shouldRecord)
        assertFalse(decision.hasPendingGroups)
        assertEquals(listOf(PostHogTriggerGroupMatch("g1", "g1", sampled = true)), decision.matchedGroups)
    }

    @Test
    fun `event leg matches per-event property filters`() {
        configure(
            group(
                id = "g1",
                conditions =
                    eventLeg(
                        mapOf(
                            "name" to "purchase",
                            "properties" to listOf(mapOf("key" to "amount", "operator" to "gt", "value" to 100)),
                        ),
                    ),
            ),
        )

        assertFalse(onEvent(event = "purchase", properties = mapOf("amount" to 50)))
        assertFalse(onEvent(event = "purchase", properties = mapOf("amount" to "free")))
        assertTrue(decide().hasPendingGroups)
        assertTrue(onEvent(event = "purchase", properties = mapOf("amount" to 150)))
        assertTrue(decide().shouldRecord)
    }

    @Test
    fun `event leg applies exact icontains regex and is_not filters`() {
        val cases =
            listOf(
                // exact
                listOf(mapOf<String, Any?>("key" to "plan", "value" to "pro")) to mapOf<String, Any?>("plan" to "pro"),
                // icontains
                listOf(mapOf<String, Any?>("key" to "email", "operator" to "icontains", "value" to "@corp")) to
                    mapOf<String, Any?>("email" to "a@CORP.io"),
                // regex
                listOf(mapOf<String, Any?>("key" to "page", "operator" to "regex", "value" to "^/checkout")) to
                    mapOf<String, Any?>("page" to "/checkout/step-2"),
                // is_not on a missing property
                listOf(mapOf<String, Any?>("key" to "tier", "operator" to "is_not", "value" to "enterprise")) to
                    mapOf<String, Any?>("unrelated" to 1),
            )
        for ((filters, properties) in cases) {
            val evaluator = PostHogTriggerGroupsEvaluator()
            val config =
                parseTriggerGroupsConfig(
                    mapOf(
                        "version" to 2,
                        "triggerGroups" to
                            listOf(
                                group(id = "g1", conditions = eventLeg(mapOf("name" to "purchase", "properties" to filters))),
                            ),
                    ),
                )
            evaluator.onConfig(config!!.groups)
            assertTrue(
                evaluator.onEvent("session-1", "purchase", properties, null),
                "filters $filters must match $properties",
            )
        }
    }

    @Test
    fun `same-name event entries are a disjunction`() {
        configure(
            group(
                id = "g1",
                conditions =
                    eventLeg(
                        mapOf(
                            "name" to "purchase",
                            "properties" to listOf(mapOf("key" to "amount", "operator" to "gt", "value" to 100)),
                        ),
                        mapOf(
                            "name" to "purchase",
                            "properties" to listOf(mapOf<String, Any?>("key" to "vip", "value" to true)),
                        ),
                    ),
            ),
        )

        assertFalse(onEvent(event = "purchase", properties = mapOf("amount" to 50)))
        assertTrue(onEvent(event = "purchase", properties = mapOf("amount" to 50, "vip" to true)))
    }

    @Test
    fun `group-level property filters gate the event leg`() {
        configure(
            group(
                id = "g1",
                conditions =
                    mapOf(
                        "events" to listOf(mapOf("name" to "purchase")),
                        "properties" to listOf(mapOf("key" to "region", "value" to "EU")),
                    ),
            ),
        )

        assertFalse(onEvent(event = "purchase", properties = mapOf("region" to "US")))
        assertTrue(onEvent(event = "purchase", properties = mapOf("region" to "EU")))
    }

    @Test
    fun `screen names match url regexes anywhere`() {
        configure(group(id = "g1", conditions = urlLeg("checkout")))

        assertFalse(screen(name = "/home"))
        assertTrue(decide().hasPendingGroups)
        assertTrue(screen(name = "/en/checkout/step-2"))
        assertTrue(decide().shouldRecord)
    }

    @Test
    fun `a non screen event never activates the url leg`() {
        configure(group(id = "g1", conditions = urlLeg("checkout")))

        assertFalse(onEvent(event = "checkout", properties = mapOf("\$screen_name" to "/checkout")))
        assertFalse(decide().shouldRecord)
    }

    @Test
    fun `flag leg matches boolean flags and variants`() {
        configure(group(id = "g1", conditions = flagLeg("replay-flag")))

        assertFalse(decide(flags = null).shouldRecord)
        assertTrue(decide().hasPendingGroups)
        assertFalse(decide(flags = mapOf("replay-flag" to false)).shouldRecord)
        assertFalse(decide(flags = mapOf("other-flag" to true)).shouldRecord)
        assertTrue(decide(flags = mapOf("replay-flag" to true)).shouldRecord)
    }

    @Test
    fun `flag leg with variant matches the variant or a boolean true`() {
        configure(group(id = "g1", conditions = flagLeg(mapOf("flag" to "replay-flag", "variant" to "beta"))))

        assertFalse(decide(flags = mapOf("replay-flag" to "alpha")).shouldRecord)
        assertTrue(decide(flags = mapOf("replay-flag" to "beta")).shouldRecord)
        // a boolean true satisfies a variant flag, matching web LinkedFlagMatching
        assertTrue(decide(flags = mapOf("replay-flag" to true)).shouldRecord)
    }

    @Test
    fun `flag leg without variant matches any non-empty variant`() {
        configure(group(id = "g1", conditions = flagLeg("replay-flag")))

        assertTrue(decide(flags = mapOf("replay-flag" to "beta")).shouldRecord)
        assertFalse(decide(flags = mapOf("replay-flag" to "")).shouldRecord)
    }

    @Test
    fun `activation is sticky within a session and resets on a new session`() {
        configure(group(id = "g1", conditions = eventLeg(mapOf("name" to "purchase"))))

        assertTrue(onEvent(event = "purchase"))
        // a later non-matching event does not deactivate (and activates nothing new)
        assertFalse(onEvent(event = "other"))
        assertTrue(decide().shouldRecord)

        var decision = decide(sessionId = "session-2")
        assertFalse(decision.shouldRecord)
        assertTrue(decision.hasPendingGroups)
        // the previous session's activation must not leak into the new one
        assertFalse(onEvent(sessionId = "session-2", event = "other"))
        decision = decide(sessionId = "session-2")
        assertFalse(decision.shouldRecord)
        assertTrue(onEvent(sessionId = "session-2", event = "purchase"))
        assertTrue(decide(sessionId = "session-2").shouldRecord)
    }

    @Test
    fun `sampling decision is deterministic per session and group`() {
        // java "session-1g-empty".hashCode() -> bucket 23; "session-2g-empty" -> bucket 12
        configure(group(id = "g-empty", sampleRate = 0.2))
        assertFalse(decide().shouldRecord)
        // same session: the stored decision is reused
        assertFalse(decide().shouldRecord)

        // a new session re-decides
        assertTrue(decide(sessionId = "session-2").shouldRecord)
    }

    @Test
    fun `a sample rate change re-decides the same session`() {
        configure(group(id = "group-1", sampleRate = 0.61))
        assertFalse(decide().shouldRecord)

        configure(group(id = "group-1", sampleRate = 0.62))
        assertTrue(decide().shouldRecord)
    }

    @Test
    fun `a missing sample rate is fully sampled`() {
        configure(group(id = "group-1", sampleRate = null))
        val decision = decide()
        assertTrue(decision.shouldRecord)
        assertEquals(listOf(true), decision.matchedGroups.map { it.sampled })
    }

    @Test
    fun `union records when any activated group is sampled in`() {
        configure(
            group(id = "group-a", sampleRate = 0.0),
            group(id = "group-b", sampleRate = 1.0),
        )

        val decision = decide()
        assertTrue(decision.shouldRecord)
        assertEquals(
            listOf(
                PostHogTriggerGroupMatch("group-a", "group-a", sampled = false),
                PostHogTriggerGroupMatch("group-b", "group-b", sampled = true),
            ),
            decision.matchedGroups,
        )
    }

    @Test
    fun `union keeps waiting while any group is pending`() {
        configure(
            group(id = "group-a", sampleRate = 0.0),
            group(id = "group-b", conditions = eventLeg(mapOf("name" to "purchase"))),
        )

        var decision = decide()
        assertFalse(decision.shouldRecord)
        assertTrue(decision.hasPendingGroups)

        assertTrue(onEvent(event = "purchase"))
        decision = decide()
        // group-b activated with rate 1.0, so the union records and stops waiting
        assertTrue(decision.shouldRecord)
        assertFalse(decision.hasPendingGroups)
    }

    @Test
    fun `union stops waiting once every group resolved`() {
        configure(
            group(id = "group-a", sampleRate = 0.0),
            group(id = "group-b", sampleRate = 0.0),
        )

        val decision = decide()
        assertFalse(decision.shouldRecord)
        assertFalse(decision.hasPendingGroups)
    }

    @Test
    fun `minimum duration is the lowest among activated groups`() {
        configure(
            group(id = "group-a", minDurationMs = 5000),
            group(id = "group-b", minDurationMs = 1000, conditions = eventLeg(mapOf("name" to "purchase"))),
        )

        var decision = decide()
        assertEquals(5000L, decision.minDurationMs)

        assertTrue(onEvent(event = "purchase"))
        decision = decide()
        assertEquals(1000L, decision.minDurationMs)
    }

    @Test
    fun `minimum duration is null without an activated group setting one`() {
        configure(
            group(id = "group-a", conditions = eventLeg(mapOf("name" to "purchase"))),
            group(id = "group-b", minDurationMs = 1000, conditions = eventLeg(mapOf("name" to "purchase"))),
        )

        val decision = decide()
        assertEquals(null, decision.minDurationMs)
    }

    @Test
    fun `unreliable event legs are disabled so they neither record nor wait`() {
        // React Native captures events in JS, so the event/screen legs can never fire natively:
        // this group has no other leg, so it resolves to disabled instead of pending forever.
        configure(group(id = "g1", conditions = eventLeg(mapOf("name" to "purchase"))))

        var decision = evaluator.evaluate("session-1", mapOf("replay-flag" to false), null, eventLegsReliable = false)
        assertFalse(decision.shouldRecord)
        assertFalse(decision.hasPendingGroups)

        // flag legs still decide under unreliable event legs
        configure(group(id = "g2", conditions = mapOf("matchType" to "any") + flagLeg("replay-flag")))
        decision = evaluator.evaluate("session-1", mapOf("replay-flag" to true), null, eventLegsReliable = false)
        assertTrue(decision.shouldRecord)
    }
}
