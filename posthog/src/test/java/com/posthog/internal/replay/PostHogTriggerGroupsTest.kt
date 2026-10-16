package com.posthog.internal.replay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogTriggerGroupsTest {
    @Test
    fun `version missing or 1 keeps the v1 config`() {
        assertNull(parseTriggerGroupsConfig(mapOf("sampleRate" to 0.5, "eventTriggers" to listOf("purchase"))))
        assertNull(
            parseTriggerGroupsConfig(mapOf("version" to 1, "triggerGroups" to listOf(group(id = "g1")))),
        )
    }

    @Test
    fun `version 2 without usable groups keeps the v1 config`() {
        assertNull(parseTriggerGroupsConfig(mapOf("version" to 2)))
        assertNull(parseTriggerGroupsConfig(mapOf("version" to 2, "triggerGroups" to emptyList<Any>())))
        // a group without an id cannot be identified, so it is dropped and v1 stays in effect
        assertNull(
            parseTriggerGroupsConfig(mapOf("version" to 2, "triggerGroups" to listOf(mapOf("name" to "no id")))),
        )
    }

    @Test
    fun `parses group sampling fields and conditions`() {
        val config =
            config(
                group(
                    id = "group-1",
                    name = "Checkout",
                    sampleRate = 0.42,
                    minDurationMs = 5000,
                    conditions =
                        mapOf(
                            "matchType" to "any",
                            "events" to listOf(mapOf("name" to "purchase")),
                            "urls" to listOf(mapOf("url" to "^/checkout", "matching" to "regex")),
                            "flag" to mapOf("flag" to "replay-flag", "variant" to "beta"),
                            "properties" to listOf(mapOf("key" to "region", "value" to "EU")),
                        ),
                ),
            )

        val group = config.groups.single()
        assertEquals("group-1", group.id)
        assertEquals("Checkout", group.name)
        assertEquals(0.42, group.sampleRate)
        assertEquals(5000L, group.minDurationMs)
        assertEquals(PostHogTriggerMatchType.ANY, group.conditions.matchType)
        assertEquals(listOf(PostHogTriggerEvent("purchase", emptyList())), group.conditions.events)
        assertEquals(listOf("^/checkout"), group.conditions.urls.map { it.pattern })
        assertEquals(PostHogTriggerFlag("replay-flag", "beta"), group.conditions.flag)
        assertEquals(listOf(PostHogTriggerPropertyFilter("region", null, null, "EU")), group.conditions.properties)
    }

    @Test
    fun `matchType defaults to all`() {
        val config = config(group(conditions = mapOf("events" to listOf(mapOf("name" to "purchase")))))
        assertEquals(PostHogTriggerMatchType.ALL, config.groups.single().conditions.matchType)
    }

    @Test
    fun `events parse with per-event property filters and drop nameless entries`() {
        val config =
            config(
                group(
                    conditions =
                        mapOf(
                            "events" to
                                listOf(
                                    mapOf(
                                        "name" to "purchase",
                                        "properties" to
                                            listOf(
                                                // remote-config numbers arrive as Doubles (Gson)
                                                mapOf("key" to "amount", "operator" to "gt", "value" to 100.0),
                                            ),
                                    ),
                                    mapOf("properties" to listOf(mapOf("key" to "orphan"))),
                                ),
                        ),
                ),
            )

        val events = config.groups.single().conditions.events
        assertEquals(1, events.size)
        assertEquals(
            listOf(PostHogTriggerPropertyFilter("amount", null, "gt", 100.0)),
            events.single().properties,
        )
    }

    @Test
    fun `urls keep only valid regex entries`() {
        val config =
            config(
                group(
                    conditions =
                        mapOf(
                            "urls" to
                                listOf(
                                    mapOf("url" to "^/checkout", "matching" to "regex"),
                                    mapOf("url" to "exact-but-unsupported", "matching" to "icontains"),
                                    mapOf("url" to "([invalid", "matching" to "regex"),
                                    mapOf("matching" to "regex"),
                                ),
                        ),
                ),
            )

        val urls = config.groups.single().conditions.urls
        assertEquals(1, urls.size)
        assertTrue(urls.single().containsMatchIn("/checkout/step-2"))
        assertFalse(urls.single().containsMatchIn("/home"))
    }

    @Test
    fun `flag accepts a bare string or a flag and variant map`() {
        val bare = config(group(conditions = mapOf("flag" to "replay-flag")))
        assertEquals(PostHogTriggerFlag("replay-flag", null), bare.groups.single().conditions.flag)

        val withVariant =
            config(
                group(
                    conditions =
                        mapOf(
                            "flag" to mapOf("flag" to "replay-flag", "variant" to "beta"),
                        ),
                ),
            )
        assertEquals(PostHogTriggerFlag("replay-flag", "beta"), withVariant.groups.single().conditions.flag)

        val noFlagName =
            config(
                group(
                    conditions =
                        mapOf(
                            "flag" to mapOf("variant" to "beta"),
                        ),
                ),
            )
        assertNull(noFlagName.groups.single().conditions.flag)
    }

    @Test
    fun `a missing or non-number sample rate parses to null`() {
        val config = config(group(sampleRate = null), group(id = "g2", sampleRate = "0.5"))
        assertEquals(null, config.groups[0].sampleRate)
        assertEquals(null, config.groups[1].sampleRate)
    }

    @Test
    fun `simple hash matches the web helper`() {
        // java "abc".hashCode() == 96354
        assertEquals(96354L, simpleTriggerHash("abc"))
        // java "posthog".hashCode() == -391202912; js Math.abs of the int32 yields 391202912
        assertEquals(391202912L, simpleTriggerHash("posthog"))
    }

    @Test
    fun `sampling uses the hash bucket like the web helper`() {
        // java "session-1group-1".hashCode() == 365459561; 365459561 % 100 == 61
        assertFalse(sampleOnTriggerProperty("session-1group-1", 0.61))
        assertTrue(sampleOnTriggerProperty("session-1group-1", 0.62))
        assertTrue(sampleOnTriggerProperty("session-1group-1", 1.0))
        assertFalse(sampleOnTriggerProperty("session-1group-1", 0.0))
    }

    private fun config(vararg groups: Map<String, Any?>): PostHogTriggerGroupsConfig {
        val config =
            parseTriggerGroupsConfig(
                mapOf(
                    "version" to 2,
                    "triggerGroups" to groups.toList(),
                ),
            )
        assertTrue(config != null)
        return config
    }
}

private fun group(
    id: String = "g1",
    name: String = "Group 1",
    sampleRate: Any? = 1.0,
    minDurationMs: Any? = null,
    conditions: Map<String, Any?> = emptyMap(),
): Map<String, Any?> =
    mapOf(
        "id" to id,
        "name" to name,
        "sampleRate" to sampleRate,
        "minDurationMs" to minDurationMs,
        "conditions" to conditions,
    )
