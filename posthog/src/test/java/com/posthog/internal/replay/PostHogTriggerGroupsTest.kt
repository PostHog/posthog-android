package com.posthog.internal.replay

import com.posthog.internal.sampleOnProperty
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
    fun `version 2 without a group list keeps the v1 config`() {
        assertNull(parseTriggerGroupsConfig(mapOf("version" to 2)))
        assertNull(parseTriggerGroupsConfig(mapOf("version" to 2, "triggerGroups" to emptyList<Any>())))
    }

    @Test
    fun `version 2 with no usable group stays v2 with no group`() {
        val logs = mutableListOf<String>()
        val unusable = listOf(mapOf("name" to "no id"), mapOf("id" to "", "sampleRate" to 0), "not a group")

        val config = parseTriggerGroupsConfig(mapOf("version" to 2, "triggerGroups" to unusable)) { logs.add(it) }

        assertEquals(PostHogTriggerGroupsConfig(emptyList()), config)
        assertEquals(1, logs.count { it.contains("no usable trigger group") })
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
    fun `a missing sample rate parses to null, a string rate parses and garbage samples in`() {
        val config =
            config(
                group(sampleRate = MISSING),
                group(id = "g2", sampleRate = "0.5"),
                group(id = "g3", sampleRate = "abc"),
                group(id = "g4", sampleRate = " 0.25 "),
                group(id = "g5", sampleRate = "  "),
                group(id = "g6", sampleRate = "NaN"),
                group(id = "g7", sampleRate = "Infinity"),
                group(id = "g8", sampleRate = "-Infinity"),
            )
        assertEquals(null, config.groups[0].sampleRate)
        assertEquals(0.5, config.groups[1].sampleRate)
        assertEquals(null, config.groups[2].sampleRate)
        assertEquals(0.25, config.groups[3].sampleRate)
        assertEquals(0.0, config.groups[4].sampleRate)
        assertEquals(null, config.groups[5].sampleRate)
        assertTrue(sampleOnProperty("session", config.groups[6].sampleRate!!))
        assertFalse(sampleOnProperty("session", config.groups[7].sampleRate!!))
    }

    @Test
    fun `a null sample rate samples out`() {
        assertEquals(0.0, config(group(sampleRate = null)).groups.single().sampleRate)
    }

    @Test
    fun `the cached copy keeps a null rate as 0 and leaves everything else alone`() {
        val sessionRecording =
            mapOf(
                "version" to 2,
                "endpoint" to "/s/",
                "triggerGroups" to
                    listOf(
                        group(id = "a", sampleRate = null),
                        group(id = "b", sampleRate = MISSING),
                        group(id = "c", sampleRate = "0.5"),
                    ),
            )
        val normalized = normalizeTriggerGroupNullSampleRates(sessionRecording)
        val groups = normalized["triggerGroups"] as List<*>
        assertEquals(0.0, (groups[0] as Map<*, *>)["sampleRate"])
        assertEquals(group(id = "b", sampleRate = MISSING), groups[1])
        assertEquals(group(id = "c", sampleRate = "0.5"), groups[2])
        assertEquals(sessionRecording - "triggerGroups", normalized - "triggerGroups")
    }

    @Test
    fun `a configured leg stays configured even when none of its entries parse`() {
        val conditions =
            config(
                group(
                    conditions =
                        mapOf(
                            "urls" to listOf(mapOf("url" to "([invalid", "matching" to "regex")),
                            "events" to listOf(mapOf("properties" to emptyList<Any>())),
                            "flag" to mapOf("variant" to "beta"),
                        ),
                ),
            ).groups.single().conditions
        assertTrue(conditions.urls.isEmpty() && conditions.events.isEmpty() && conditions.flag == null)
        assertTrue(conditions.hasUrlsLeg && conditions.hasEventsLeg && conditions.hasFlagLeg)
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
    ).filterValues { it !== MISSING }

// Leaves the sampleRate key out of a group, as opposed to sending it as JSON null.
private val MISSING = Any()
