package com.posthog.internal.replay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Leaves the sampleRate key out of a group, as opposed to sending it as JSON null.
private val NO_SAMPLE_RATE = Any()

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
        ).filterValues { it !== NO_SAMPLE_RATE }

    private fun eventLeg(vararg events: Map<String, Any?>) = mapOf("events" to events.toList())

    private fun urlLeg(pattern: String) = mapOf("urls" to listOf(mapOf("url" to pattern, "matching" to "regex")))

    private fun flagLeg(flag: Any) = mapOf("flag" to flag)

    private fun onEvent(
        sessionId: String = "session-1",
        event: String,
        properties: Map<String, Any?>? = null,
        personProperties: Map<String, Any?>? = null,
    ): Boolean = evaluator.onEvent(sessionId, event, properties, personProperties)

    private fun screen(
        sessionId: String = "session-1",
        name: String,
        personProperties: Map<String, Any?>? = null,
    ): Boolean =
        onEvent(
            sessionId = sessionId,
            event = "\$screen",
            properties = mapOf("\$screen_name" to name),
            personProperties = personProperties,
        )

    private fun decide(
        sessionId: String = "session-1",
        flags: Map<String, Any?>? = emptyMap(),
    ) = evaluator.evaluate(sessionId, flags)

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
        configure(group(id = "group-1", sampleRate = NO_SAMPLE_RATE))
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
    fun `an unparseable condition keeps the group pending instead of recording every session`() {
        configure(group(id = "g1", conditions = mapOf("urls" to listOf(mapOf("url" to "([invalid", "matching" to "regex")))))
        var decision = decide()
        assertFalse(decision.shouldRecord)
        assertTrue(decision.hasPendingGroups)

        // under all, the broken leg still has to match, so the event alone does not record
        configure(
            group(
                id = "g2",
                conditions =
                    eventLeg(
                        mapOf("name" to "purchase"),
                    ) + mapOf("urls" to listOf(mapOf("url" to "/checkout", "matching" to "exact"))),
            ),
        )
        assertTrue(onEvent(event = "purchase"))
        decision = decide()
        assertFalse(decision.shouldRecord)
        assertTrue(decision.hasPendingGroups)
    }

    @Test
    fun `evaluating a stale session does not wipe the current session's activation`() {
        configure(group(id = "g1", conditions = eventLeg(mapOf("name" to "purchase"))))
        assertTrue(onEvent(sessionId = "session-2", event = "purchase"))

        assertFalse(decide(sessionId = "session-1").shouldRecord)
        assertTrue(decide(sessionId = "session-2").shouldRecord)
    }

    @Test
    fun `pending groups list each pending leg by group name`() {
        configure(
            group(
                id = "checkout",
                conditions = eventLeg(mapOf("name" to "purchase")) + urlLeg("^/checkout") + flagLeg("replay-flag"),
            ),
            group(id = "always", conditions = emptyMap()),
        )
        // the helper names a group after its id
        assertEquals(
            listOf(
                "trigger group \"checkout\": URL condition not matched",
                "trigger group \"checkout\": event condition not matched",
                "trigger group \"checkout\": linked flag condition not matched",
            ),
            decide().pendingConditions,
        )

        assertTrue(screen(name = "/checkout"))
        assertEquals(
            listOf(
                "trigger group \"checkout\": event condition not matched",
                "trigger group \"checkout\": linked flag condition not matched",
            ),
            decide().pendingConditions,
        )
    }

    @Test
    fun `a group that is not pending lists no conditions`() {
        configure(
            group(
                id = "any",
                conditions = mapOf("matchType" to "any") + eventLeg(mapOf("name" to "purchase")) + urlLeg("^/checkout"),
            ),
        )
        assertTrue(screen(name = "/checkout"))
        // activated through the screen leg, so the still-pending event leg is not reported
        assertTrue(decide().pendingConditions.isEmpty())
    }

    private fun screenGroupWithFilter(filter: Map<String, Any?>) =
        group(id = "g1", conditions = urlLeg("^/checkout") + mapOf("properties" to listOf(filter)))

    @Test
    fun `group-level event filters never match on the screen leg`() {
        configure(screenGroupWithFilter(mapOf("key" to "\$screen_name", "operator" to "exact", "value" to "/checkout")))
        assertFalse(screen(name = "/checkout"))
        assertFalse(decide().shouldRecord)
    }

    @Test
    fun `group-level negative event filters match on the screen leg`() {
        configure(screenGroupWithFilter(mapOf("key" to "\$screen_name", "operator" to "is_not", "value" to "/checkout")))
        assertTrue(screen(name = "/checkout"))
        assertTrue(decide().shouldRecord)
    }

    @Test
    fun `a new session checks the last seen screen again`() {
        configure(group(id = "g1", conditions = urlLeg("checkout")))
        assertTrue(screen(name = "/checkout"))

        assertTrue(evaluator.checkScreenOnNewSession("session-2", null))
        assertTrue(decide(sessionId = "session-2").shouldRecord)
        // already activated, so the same screen activates nothing new
        assertFalse(screen(sessionId = "session-2", name = "/checkout"))
    }

    @Test
    fun `a new session on a screen that does not match stays pending`() {
        configure(group(id = "g1", conditions = urlLeg("checkout")))
        assertTrue(screen(name = "/checkout"))
        assertFalse(screen(name = "/home"))

        assertFalse(evaluator.checkScreenOnNewSession("session-2", null))
        val decision = decide(sessionId = "session-2")
        assertFalse(decision.shouldRecord)
        assertTrue(decision.hasPendingGroups)
    }

    @Test
    fun `a new session without a seen screen stays pending`() {
        configure(group(id = "g1", conditions = urlLeg("checkout")))

        assertFalse(evaluator.checkScreenOnNewSession("session-2", null))
        assertTrue(decide(sessionId = "session-2").hasPendingGroups)
    }

    @Test
    fun `the first event of a new session checks the last seen screen again`() {
        configure(group(id = "g1", conditions = urlLeg("checkout")))
        assertTrue(screen(name = "/checkout"))

        assertTrue(onEvent(sessionId = "session-2", event = "other"))
        assertTrue(decide(sessionId = "session-2").shouldRecord)
    }

    @Test
    fun `a new session that opens on another screen does not match the screen it left`() {
        configure(group(id = "g1", conditions = urlLeg("checkout")))
        assertTrue(screen(name = "/checkout"))

        assertFalse(screen(sessionId = "session-2", name = "/home"))
        assertTrue(decide(sessionId = "session-2").hasPendingGroups)
    }

    @Test
    fun `a new session does not check the event leg again`() {
        configure(group(id = "g1", conditions = eventLeg(mapOf("name" to "purchase"))))
        assertTrue(onEvent(event = "purchase"))

        assertFalse(evaluator.checkScreenOnNewSession("session-2", null))
        assertTrue(decide(sessionId = "session-2").hasPendingGroups)
    }

    @Test
    fun `a new session checks the last seen screen against the current person properties`() {
        configure(screenGroupWithFilter(mapOf("key" to "plan", "type" to "person", "value" to "pro")))
        assertTrue(screen(name = "/checkout", personProperties = mapOf("plan" to "pro")))

        assertFalse(evaluator.checkScreenOnNewSession("session-2", mapOf("plan" to "free")))
        assertTrue(decide(sessionId = "session-2").hasPendingGroups)
        assertTrue(evaluator.checkScreenOnNewSession("session-3", mapOf("plan" to "pro")))
        assertTrue(decide(sessionId = "session-3").shouldRecord)
    }

    @Test
    fun `a new session checks the last seen screen without event properties`() {
        configure(screenGroupWithFilter(mapOf("key" to "\$screen_name", "operator" to "exact", "value" to "/checkout")))
        assertFalse(screen(name = "/checkout"))

        assertFalse(evaluator.checkScreenOnNewSession("session-2", null))
        assertFalse(decide(sessionId = "session-2").shouldRecord)
    }

    @Test
    fun `a session change to the same session activates nothing`() {
        configure(group(id = "g1", conditions = urlLeg("checkout")))
        assertTrue(screen(name = "/checkout"))

        assertFalse(evaluator.checkScreenOnNewSession("session-1", null))
        assertTrue(decide().shouldRecord)
    }

    private fun rememberScreen(
        sessionId: String? = "session-1",
        name: String,
    ) = evaluator.rememberScreen(sessionId, "\$screen", mapOf("\$screen_name" to name))

    @Test
    fun `a remembered screen activates nothing until the next session is checked`() {
        configure(group(id = "g1", conditions = urlLeg("checkout")))
        rememberScreen(name = "/checkout")
        assertFalse(decide().shouldRecord)

        assertTrue(evaluator.checkScreenOnNewSession("session-2", null))
        assertTrue(decide(sessionId = "session-2").shouldRecord)
    }

    @Test
    fun `a remembered screen replaces the screen a group matched`() {
        configure(group(id = "g1", conditions = urlLeg("checkout")))
        assertTrue(screen(name = "/checkout"))
        rememberScreen(name = "/home")

        assertFalse(evaluator.checkScreenOnNewSession("session-2", null))
        assertTrue(decide(sessionId = "session-2").hasPendingGroups)
    }

    @Test
    fun `a session is not checked against a screen event of its own`() {
        configure(group(id = "g1", conditions = urlLeg("checkout")))
        rememberScreen(sessionId = "session-2", name = "/checkout")

        assertFalse(evaluator.checkScreenOnNewSession("session-2", null))
        assertTrue(decide(sessionId = "session-2").hasPendingGroups)
    }

    @Test
    fun `a session is checked against the last seen screen only once`() {
        configure(group(id = "g1", conditions = urlLeg("home")))
        assertFalse(screen(name = "/checkout"))
        assertFalse(evaluator.checkScreenOnNewSession("session-2", null))

        configure(group(id = "g1", conditions = urlLeg("checkout")))
        assertFalse(evaluator.checkScreenOnNewSession("session-2", null))
        assertTrue(decide(sessionId = "session-2").hasPendingGroups)
        assertTrue(evaluator.checkScreenOnNewSession("session-3", null))
    }

    @Test
    fun `a skipped screen check leaves the session to its own screen event`() {
        configure(group(id = "g1", conditions = urlLeg("checkout")))
        assertTrue(screen(name = "/checkout"))
        evaluator.skipScreenCheck("session-2")

        assertFalse(evaluator.checkScreenOnNewSession("session-2", null))
        assertFalse(onEvent(sessionId = "session-2", event = "other"))
        assertTrue(decide(sessionId = "session-2").hasPendingGroups)
        assertFalse(screen(sessionId = "session-2", name = "/home"))
        assertTrue(screen(sessionId = "session-2", name = "/checkout"))
    }

    @Test
    fun `a remembered screen is checked in its own session only on request`() {
        configure(group(id = "g1", conditions = urlLeg("checkout")))
        rememberScreen(name = "/checkout")
        assertFalse(evaluator.checkScreenOnNewSession("session-1", null))

        assertTrue(evaluator.checkScreenOnNewSession("session-1", null, includeUnevaluatedScreen = true))
        assertTrue(decide().shouldRecord)
        assertFalse(evaluator.checkScreenOnNewSession("session-1", null, includeUnevaluatedScreen = true))
    }

    @Test
    fun `a remembered screen is checked on request in a session that was already checked`() {
        configure(group(id = "g1", conditions = urlLeg("checkout")))
        assertFalse(screen(name = "/home"))
        assertFalse(evaluator.checkScreenOnNewSession("session-2", null))
        rememberScreen(sessionId = "session-2", name = "/checkout")

        assertTrue(evaluator.checkScreenOnNewSession("session-2", null, includeUnevaluatedScreen = true))
    }

    @Test
    fun `a screen evaluated live is not checked again in its session`() {
        configure(screenGroupWithFilter(mapOf("key" to "plan", "type" to "person", "value" to "pro")))
        assertFalse(screen(name = "/checkout", personProperties = mapOf("plan" to "free")))

        assertFalse(evaluator.checkScreenOnNewSession("session-1", mapOf("plan" to "pro"), includeUnevaluatedScreen = true))
        assertTrue(decide().hasPendingGroups)
    }

    @Test
    fun `a skipped screen check drops the remembered screen the app left`() {
        configure(group(id = "g1", conditions = urlLeg("checkout")))
        rememberScreen(name = "/checkout")
        evaluator.skipScreenCheck("session-2")

        assertFalse(evaluator.checkScreenOnNewSession("session-2", null, includeUnevaluatedScreen = true))
    }

    @Test
    fun `a group load checks a screen again that was evaluated live`() {
        configure(screenGroupWithFilter(mapOf("key" to "plan", "type" to "person", "value" to "pro")))
        assertFalse(screen(name = "/checkout", personProperties = mapOf("plan" to "free")))

        evaluator.onGroupsLoaded()

        assertFalse(evaluator.checkScreenOnNewSession("session-1", mapOf("plan" to "pro")), "not without the request")
        assertTrue(evaluator.checkScreenOnNewSession("session-1", mapOf("plan" to "pro"), includeUnevaluatedScreen = true))
        assertTrue(decide().shouldRecord)
        assertFalse(evaluator.checkScreenOnNewSession("session-1", mapOf("plan" to "pro"), includeUnevaluatedScreen = true))
    }

    @Test
    fun `a group load checks the screen of an older session in a session that was already checked`() {
        configure(group(id = "g1", conditions = urlLeg("home")))
        assertFalse(screen(name = "/checkout"))
        assertFalse(evaluator.checkScreenOnNewSession("session-2", null))

        configure(group(id = "g1", conditions = urlLeg("home")), group(id = "g2", conditions = urlLeg("checkout")))
        evaluator.onGroupsLoaded()

        assertTrue(evaluator.checkScreenOnNewSession("session-2", null, includeUnevaluatedScreen = true))
        assertEquals(listOf("g2"), decide(sessionId = "session-2").matchedGroups.map { it.id })
    }

    @Test
    fun `a group load keeps an activated group activated`() {
        configure(
            group(id = "g1", conditions = eventLeg(mapOf("name" to "purchase"))),
            group(id = "g2", conditions = urlLeg("checkout")),
        )
        assertTrue(onEvent(event = "purchase"))
        assertTrue(screen(name = "/checkout"))

        evaluator.onGroupsLoaded()

        assertFalse(evaluator.checkScreenOnNewSession("session-1", null, includeUnevaluatedScreen = true))
        assertEquals(listOf("g1", "g2"), decide().matchedGroups.map { it.id })
    }

    @Test
    fun `a group load does not bring back the screen the app left`() {
        configure(group(id = "g1", conditions = urlLeg("checkout")))
        rememberScreen(name = "/checkout")
        evaluator.skipScreenCheck("session-2")

        evaluator.onGroupsLoaded()

        assertFalse(evaluator.checkScreenOnNewSession("session-2", null, includeUnevaluatedScreen = true))
    }

    @Test
    fun `a late event for a session that was left does not clear the newer session`() {
        configure(group(id = "g1", conditions = eventLeg(mapOf("name" to "purchase"))))
        assertTrue(onEvent(sessionId = "session-a", event = "purchase"))
        assertTrue(onEvent(sessionId = "session-b", event = "purchase"))

        assertFalse(onEvent(sessionId = "session-a", event = "purchase"))

        assertTrue(decide(sessionId = "session-b").shouldRecord)
        assertFalse(decide(sessionId = "session-a").shouldRecord)
    }

    @Test
    fun `a late screen check for a session that was left does not clear the newer session`() {
        configure(group(id = "g1", conditions = urlLeg("checkout")))
        assertTrue(screen(sessionId = "session-0", name = "/checkout"))
        assertTrue(evaluator.checkScreenOnNewSession("session-a", null))
        assertTrue(evaluator.checkScreenOnNewSession("session-b", null))

        assertFalse(evaluator.checkScreenOnNewSession("session-a", null, includeUnevaluatedScreen = true))

        assertTrue(decide(sessionId = "session-b").shouldRecord)
    }

    @Test
    fun `a late skipped check for a session that was left keeps the remembered screen`() {
        configure(group(id = "g1", conditions = urlLeg("checkout")))
        assertTrue(screen(sessionId = "session-a", name = "/checkout"))
        assertTrue(evaluator.checkScreenOnNewSession("session-b", null))

        evaluator.skipScreenCheck("session-a")

        assertTrue(evaluator.checkScreenOnNewSession("session-c", null))
    }

    @Test
    fun `a late screen event for a session that was left does not replace the screen of the newer session`() {
        configure(group(id = "g1", conditions = urlLeg("checkout")))
        assertFalse(screen(sessionId = "session-a", name = "/home"))
        assertFalse(screen(sessionId = "session-b", name = "/home"))

        assertFalse(screen(sessionId = "session-a", name = "/checkout"))
        rememberScreen(sessionId = "session-a", name = "/checkout")

        assertFalse(evaluator.checkScreenOnNewSession("session-b", null, includeUnevaluatedScreen = true))
        assertFalse(evaluator.checkScreenOnNewSession("session-c", null))
    }

    @Test
    fun `a late screen event is remembered and not evaluated while the newer session has no screen`() {
        configure(group(id = "g1", conditions = urlLeg("checkout")))
        assertFalse(screen(sessionId = "session-a", name = "/home"))
        assertFalse(onEvent(sessionId = "session-b", event = "other"))

        assertFalse(screen(sessionId = "session-a", name = "/checkout"))
        assertFalse(decide(sessionId = "session-b").shouldRecord)

        assertTrue(evaluator.checkScreenOnNewSession("session-b", null, includeUnevaluatedScreen = true))
    }
}
