package com.posthog.server

import com.posthog.internal.EvaluationReason
import com.posthog.internal.FeatureFlag
import com.posthog.internal.FeatureFlagMetadata
import com.posthog.server.internal.EvaluationsHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogFeatureFlagEvaluationsTest {
    private class RecordedCall(
        val distinctId: String,
        val key: String,
        val value: Any?,
        val properties: Map<String, Any>,
    )

    private class FakeHost(override val warningsEnabled: Boolean = true) : EvaluationsHost {
        val captures = mutableListOf<RecordedCall>()
        val warnings = mutableListOf<String>()

        override fun captureFeatureFlagCalled(
            distinctId: String,
            key: String,
            value: Any?,
            properties: Map<String, Any>,
        ) {
            captures.add(RecordedCall(distinctId, key, value, properties))
        }

        override fun logWarning(message: String) {
            warnings.add(message)
        }
    }

    private fun flag(
        key: String,
        enabled: Boolean = true,
        variant: String? = null,
        id: Int = 7,
        version: Int = 3,
        payload: String? = null,
        reason: EvaluationReason? = EvaluationReason("condition_match", "Condition matched", 0),
    ): FeatureFlag =
        FeatureFlag(
            key = key,
            enabled = enabled,
            variant = variant,
            metadata = FeatureFlagMetadata(id = id, payload = payload, version = version),
            reason = reason,
        )

    private fun snapshot(
        distinctId: String? = "user-1",
        flags: Map<String, FeatureFlag> = emptyMap(),
        locallyEvaluated: Map<String, Boolean> = flags.mapValues { false },
        host: EvaluationsHost = FakeHost(),
        requestId: String? = "req-1",
        evaluatedAt: Long? = 1_700_000_000_000L,
        definitionsLoadedAt: Long? = null,
        responseError: String? = null,
    ) = PostHogFeatureFlagEvaluations(
        distinctId = distinctId,
        flagMap = flags,
        locallyEvaluated = locallyEvaluated,
        requestId = requestId,
        evaluatedAt = evaluatedAt,
        definitionsLoadedAt = definitionsLoadedAt,
        responseError = responseError,
        host = host,
    )

    @Test
    fun `isEnabled returns false for unknown flags and does not fire an event`() {
        val host = FakeHost()
        val snapshot = snapshot(host = host, flags = mapOf("known" to flag("known", enabled = true)))

        val unknown = snapshot.isEnabled("missing")

        assertFalse(unknown)
        assertTrue(host.captures.isEmpty(), "no event should fire when the flag is unknown")
    }

    @Test
    fun `isEnabled fires capture call with full metadata`() {
        val host = FakeHost()
        val snapshot =
            snapshot(
                host = host,
                flags = mapOf("known" to flag("known", enabled = true, id = 11, version = 4)),
            )

        snapshot.isEnabled("known")

        // Dedup happens inside the real host (PostHogStateless.captureFeatureFlagCalledEvent),
        // not in the snapshot — see PostHogEvaluateFlagsTest for the end-to-end dedup case.
        val call = host.captures.first()
        assertEquals("user-1", call.distinctId)
        assertEquals("known", call.key)
        assertEquals(true, call.value)
        assertEquals(11, call.properties["\$feature_flag_id"])
        assertEquals(4, call.properties["\$feature_flag_version"])
        assertEquals("Condition matched", call.properties["\$feature_flag_reason"])
        assertEquals("req-1", call.properties["\$feature_flag_request_id"])
    }

    @Test
    fun `getFlag returns variant string and fires the event with variant value`() {
        val host = FakeHost()
        val snapshot =
            snapshot(
                host = host,
                flags = mapOf("variant-flag" to flag("variant-flag", enabled = true, variant = "control")),
            )

        val value = snapshot.getFlag("variant-flag")

        assertEquals("control", value)
        assertEquals(1, host.captures.size)
        assertEquals("control", host.captures.single().value)
    }

    @Test
    fun `getFlagPayload does not fire an event and does not record access`() {
        val host = FakeHost()
        val snapshot =
            snapshot(
                host = host,
                flags = mapOf("payload-flag" to flag("payload-flag", enabled = true, payload = "{\"a\":1}")),
            )

        val payload = snapshot.getFlagPayload("payload-flag")

        assertEquals("{\"a\":1}", payload)
        assertTrue(host.captures.isEmpty(), "payload reads should be event-free")
        // onlyAccessed() falls back to all flags because nothing was actually accessed
        val filtered = snapshot.onlyAccessed()
        assertEquals(listOf("payload-flag"), filtered.keys)
    }

    @Test
    fun `only drops unknown keys with a warning`() {
        val host = FakeHost()
        val snapshot =
            snapshot(
                host = host,
                flags =
                    mapOf(
                        "a" to flag("a"),
                        "b" to flag("b"),
                    ),
            )

        val filtered = snapshot.only(listOf("a", "missing"))

        assertEquals(listOf("a"), filtered.keys)
        assertEquals(1, host.warnings.size)
        assertTrue(host.warnings.single().contains("missing"))
    }

    @Test
    fun `onlyAccessed warns and falls back to all flags when nothing was accessed`() {
        val host = FakeHost()
        val snapshot =
            snapshot(
                host = host,
                flags =
                    mapOf(
                        "a" to flag("a"),
                        "b" to flag("b"),
                    ),
            )

        val filtered = snapshot.onlyAccessed()

        assertEquals(setOf("a", "b"), filtered.keys.toSet())
        assertEquals(1, host.warnings.size)
        assertTrue(host.warnings.single().contains("onlyAccessed"))
    }

    @Test
    fun `onlyAccessed returns only previously accessed flags`() {
        val host = FakeHost()
        val snapshot =
            snapshot(
                host = host,
                flags =
                    mapOf(
                        "a" to flag("a"),
                        "b" to flag("b"),
                    ),
            )

        snapshot.isEnabled("a")
        val filtered = snapshot.onlyAccessed()

        assertEquals(listOf("a"), filtered.keys)
        assertTrue(host.warnings.isEmpty())
    }

    @Test
    fun `filtered snapshot accessed set is independent of parent`() {
        val host = FakeHost()
        val parent =
            snapshot(
                host = host,
                flags =
                    mapOf(
                        "a" to flag("a"),
                        "b" to flag("b"),
                    ),
            )
        parent.isEnabled("a")

        val child = parent.onlyAccessed()
        child.isEnabled("a") // accessing on the child should not change parent's accessed set

        // Parent re-filtering should still only show "a"
        assertEquals(listOf("a"), parent.onlyAccessed().keys)
    }

    @Test
    fun `empty distinctId snapshot does not fire events but still records access`() {
        val host = FakeHost()
        val snapshot =
            snapshot(
                distinctId = "",
                host = host,
                flags = mapOf("a" to flag("a")),
            )

        snapshot.isEnabled("a")

        assertTrue(host.captures.isEmpty(), "empty distinctId must not leak \$feature_flag_called events")
        assertEquals(listOf("a"), snapshot.onlyAccessed().keys)
    }

    @Test
    fun `locally evaluated flag tags event with locally_evaluated and reason`() {
        val host = FakeHost()
        val flagDef =
            flag(
                "local",
                enabled = true,
                reason = EvaluationReason("local_evaluation", "Evaluated locally", null),
            )
        val snapshot =
            snapshot(
                host = host,
                flags = mapOf("local" to flagDef),
                locallyEvaluated = mapOf("local" to true),
                definitionsLoadedAt = 1_700_000_500_000L,
            )

        snapshot.isEnabled("local")

        val props = host.captures.single().properties
        assertEquals(true, props["locally_evaluated"])
        assertEquals("Evaluated locally", props["\$feature_flag_reason"])
        assertEquals(1_700_000_500_000L, props["\$feature_flag_definitions_loaded_at"])
    }

    @Test
    fun `featureFlagsLogWarnings disabled host suppresses filter warnings`() {
        val quietHost = FakeHost(warningsEnabled = false)
        val snapshot =
            snapshot(
                host = quietHost,
                flags = mapOf("a" to flag("a")),
            )

        snapshot.only(listOf("missing"))
        snapshot.onlyAccessed()

        assertTrue(quietHost.warnings.isEmpty(), "warnings should be suppressed when disabled")
    }

    @Test
    fun `keys exposes the snapshotted flag keys`() {
        val host = FakeHost()
        val snapshot =
            snapshot(
                host = host,
                flags =
                    linkedMapOf(
                        "a" to flag("a"),
                        "b" to flag("b"),
                    ),
            )

        assertEquals(listOf("a", "b"), snapshot.keys)
    }

    @Test
    fun `getFlag returns null for unknown keys`() {
        val host = FakeHost()
        val snapshot = snapshot(host = host, flags = mapOf("known" to flag("known")))

        assertNull(snapshot.getFlag("missing"))
    }

    @Test
    fun `response-level error is propagated to feature_flag_called events`() {
        val host = FakeHost()
        val snapshot =
            snapshot(
                host = host,
                flags = mapOf("a" to flag("a")),
                responseError = "errors_while_computing_flags,quota_limited",
            )

        snapshot.isEnabled("a")

        val props = host.captures.single().properties
        assertEquals("errors_while_computing_flags,quota_limited", props["\$feature_flag_error"])
    }

    @Test
    fun `null response-level error means no feature_flag_error key`() {
        val host = FakeHost()
        val snapshot =
            snapshot(
                host = host,
                flags = mapOf("a" to flag("a")),
                responseError = null,
            )

        snapshot.isEnabled("a")

        val props = host.captures.single().properties
        assertFalse(props.containsKey("\$feature_flag_error"))
    }
}
