package com.posthog.server.openfeature

import com.posthog.server.PostHogEvaluateFlagsOptions
import com.posthog.server.PostHogFeatureFlagEvaluations
import com.posthog.server.PostHogInterface
import dev.openfeature.sdk.Client
import dev.openfeature.sdk.ErrorCode
import dev.openfeature.sdk.FeatureProvider
import dev.openfeature.sdk.ImmutableContext
import dev.openfeature.sdk.ImmutableStructure
import dev.openfeature.sdk.OpenFeatureAPI
import dev.openfeature.sdk.Reason
import dev.openfeature.sdk.Value
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogProviderTest {
    private val api = OpenFeatureAPI.getInstance()
    private val posthog = mock<PostHogInterface>()
    private val snapshot = mock<PostHogFeatureFlagEvaluations>()

    @AfterTest
    fun tearDown() {
        api.shutdown()
    }

    private fun getSut(
        defaultDistinctId: String? = null,
        flags: Map<String, Any> = emptyMap(),
        payloads: Map<String, String> = emptyMap(),
    ): Client {
        whenever(snapshot.keys).doReturn(flags.keys.toList())
        flags.forEach { (key, value) -> whenever(snapshot.getFlag(key)).doReturn(value) }
        payloads.forEach { (key, payload) -> whenever(snapshot.getFlagPayload(key)).doReturn(payload) }
        whenever(posthog.evaluateFlags(anyOrNull(), any<PostHogEvaluateFlagsOptions>())).doReturn(snapshot)

        api.setProviderAndWait(PostHogProvider(posthog, defaultDistinctId))
        return api.client
    }

    private fun user(attributes: Map<String, Value> = emptyMap()) = ImmutableContext("user-1", attributes)

    private fun capturedOptions(): Pair<String?, PostHogEvaluateFlagsOptions> {
        val distinctId = argumentCaptor<String>()
        val options = argumentCaptor<PostHogEvaluateFlagsOptions>()
        verify(posthog).evaluateFlags(distinctId.capture(), options.capture())
        return distinctId.firstValue to options.firstValue
    }

    @Test
    fun `reports provider name and preloads flag definitions`() {
        getSut()

        assertEquals("PostHogProvider", api.providerMetadata.name)
        verify(posthog).reloadFeatureFlags()
    }

    @Test
    fun `provider is ready when preloading flag definitions fails`() {
        whenever(posthog.reloadFeatureFlags()).doThrow(RuntimeException("boom"))
        val sut = getSut(flags = mapOf("flag" to true))

        val details = sut.getBooleanDetails("flag", false, user())

        assertTrue(details.value)
        assertNull(details.errorCode)
    }

    @Test
    fun `shutdown does not close the client`() {
        getSut()

        api.shutdown()

        verify(posthog, never()).close()
    }

    @Test
    fun `boolean returns enabled with targeting match`() {
        val sut = getSut(flags = mapOf("flag" to true))

        val details = sut.getBooleanDetails("flag", false, user())

        assertTrue(details.value)
        assertEquals(Reason.TARGETING_MATCH.name, details.reason)
        assertNull(details.variant)
    }

    @Test
    fun `boolean returns false with default reason when flag is off`() {
        val sut = getSut(flags = mapOf("flag" to false))

        val details = sut.getBooleanDetails("flag", true, user())

        assertEquals(false, details.value)
        assertEquals(Reason.DEFAULT.name, details.reason)
        assertNull(details.errorCode)
    }

    @Test
    fun `boolean is true for a multivariate flag`() {
        val sut = getSut(flags = mapOf("flag" to "test"))

        val details = sut.getBooleanDetails("flag", false, user())

        assertTrue(details.value)
        assertEquals("test", details.variant)
    }

    @Test
    fun `unknown flag returns flag not found`() {
        val sut = getSut()

        val details = sut.getBooleanDetails("missing", true, user())

        assertTrue(details.value)
        assertEquals(ErrorCode.FLAG_NOT_FOUND, details.errorCode)
        assertEquals(Reason.ERROR.name, details.reason)
    }

    @Test
    fun `missing targeting key returns targeting key missing`() {
        val sut = getSut(flags = mapOf("flag" to true))

        val details = sut.getBooleanDetails("flag", false, ImmutableContext())

        assertEquals(false, details.value)
        assertEquals(ErrorCode.TARGETING_KEY_MISSING, details.errorCode)
        verify(posthog, never()).evaluateFlags(anyOrNull(), any<PostHogEvaluateFlagsOptions>())
    }

    @Test
    fun `missing targeting key uses the default distinct id`() {
        val sut = getSut(defaultDistinctId = "anonymous", flags = mapOf("flag" to true))

        val details = sut.getBooleanDetails("flag", false)

        assertTrue(details.value)
        assertEquals("anonymous", capturedOptions().first)
    }

    @Test
    fun `maps the evaluation context to posthog options`() {
        val sut = getSut(flags = mapOf("flag" to true))
        val context =
            user(
                mapOf(
                    "email" to Value("user@example.com"),
                    "age" to Value(42),
                    "beta" to Value(true),
                    "signedUpAt" to Value(Instant.parse("2026-01-02T03:04:05Z")),
                    "tags" to Value(listOf(Value("a"), Value("b"))),
                    "groups" to Value(ImmutableStructure(mapOf("company" to Value("acme")))),
                    "groupProperties" to
                        Value(
                            ImmutableStructure(
                                mapOf("company" to Value(ImmutableStructure(mapOf("plan" to Value("enterprise"))))),
                            ),
                        ),
                ),
            )

        sut.getBooleanValue("flag", false, context)

        val (distinctId, options) = capturedOptions()
        assertEquals("user-1", distinctId)
        assertEquals(listOf("flag"), options.flagKeys)
        assertEquals(mapOf("company" to "acme"), options.groups)
        assertEquals(mapOf("company" to mapOf<String, Any?>("plan" to "enterprise")), options.groupProperties)
        assertEquals(
            mapOf<String, Any?>(
                "email" to "user@example.com",
                "age" to 42,
                "beta" to true,
                "signedUpAt" to "2026-01-02T03:04:05Z",
                "tags" to listOf("a", "b"),
            ),
            options.personProperties,
        )
    }

    @Test
    fun `empty context sends no properties`() {
        val sut = getSut(flags = mapOf("flag" to true))

        sut.getBooleanValue("flag", false, user())

        val options = capturedOptions().second
        assertNull(options.groups)
        assertNull(options.personProperties)
        assertNull(options.groupProperties)
    }

    @Test
    fun `evaluates each call once`() {
        val sut = getSut(flags = mapOf("flag" to true))

        sut.getBooleanValue("flag", false, user())
        sut.getStringValue("flag", "default", user())

        verify(posthog, times(2)).evaluateFlags(eq("user-1"), any<PostHogEvaluateFlagsOptions>())
    }

    @Test
    fun `string returns the variant`() {
        val sut = getSut(flags = mapOf("flag" to "test"))

        val details = sut.getStringDetails("flag", "default", user())

        assertEquals("test", details.value)
        assertEquals("test", details.variant)
        assertEquals(Reason.TARGETING_MATCH.name, details.reason)
    }

    @Test
    fun `string returns the default when flag is off`() {
        val sut = getSut(flags = mapOf("flag" to false))

        val details = sut.getStringDetails("flag", "default", user())

        assertEquals("default", details.value)
        assertEquals(Reason.DEFAULT.name, details.reason)
        assertNull(details.errorCode)
    }

    @Test
    fun `string returns type mismatch for an enabled boolean flag`() {
        val sut = getSut(flags = mapOf("flag" to true))

        val details = sut.getStringDetails("flag", "default", user())

        assertEquals("default", details.value)
        assertEquals(ErrorCode.TYPE_MISMATCH, details.errorCode)
    }

    @Test
    fun `integer parses decimal and hexadecimal variants`() {
        val sut = getSut(flags = mapOf("decimal" to "42", "hex" to "0x10"))

        assertEquals(42, sut.getIntegerValue("decimal", 0, user()))
        assertEquals(16, sut.getIntegerValue("hex", 0, user()))
    }

    @Test
    fun `integer returns type mismatch for a non-integer variant`() {
        val sut = getSut(flags = mapOf("flag" to "1.5"))

        val details = sut.getIntegerDetails("flag", 7, user())

        assertEquals(7, details.value)
        assertEquals(ErrorCode.TYPE_MISMATCH, details.errorCode)
    }

    @Test
    fun `long parses large variants`() {
        val sut = getSut(flags = mapOf("flag" to "9000000000"))

        assertEquals(9_000_000_000L, sut.getLongValue("flag", 0L, user()))
    }

    @Test
    fun `double parses the variant`() {
        val sut = getSut(flags = mapOf("flag" to "1.5"))

        val details = sut.getDoubleDetails("flag", 0.0, user())

        assertEquals(1.5, details.value)
        assertEquals("1.5", details.variant)
    }

    @Test
    fun `double returns type mismatch for non-finite variants`() {
        val sut = getSut(flags = mapOf("nan" to "NaN", "text" to "control"))

        assertEquals(ErrorCode.TYPE_MISMATCH, sut.getDoubleDetails("nan", 0.0, user()).errorCode)
        assertEquals(ErrorCode.TYPE_MISMATCH, sut.getDoubleDetails("text", 0.0, user()).errorCode)
    }

    @Test
    fun `number returns the default when flag is off`() {
        val sut = getSut(flags = mapOf("flag" to false))

        val details = sut.getIntegerDetails("flag", 3, user())

        assertEquals(3, details.value)
        assertNull(details.errorCode)
    }

    @Test
    fun `missing flag still reads the snapshot so the failure is captured`() {
        val sut = getSut()

        sut.getBooleanValue("missing", false, user())

        verify(snapshot).getFlag("missing")
    }

    @Test
    fun `ignores null entries in the evaluation context`() {
        val sut = getSut(flags = mapOf("flag" to true))
        val nested = HashMap<String, Value?>().apply { put("plan", null) }
        val groups = HashMap<String, Value?>().apply { put("company", null) }
        val context =
            ImmutableContext(
                "user-1",
                HashMap<String, Value?>().apply {
                    put("optional", null)
                    put("nested", Value(ImmutableStructure(nested)))
                    put("groups", Value(ImmutableStructure(groups)))
                    put("groupProperties", null)
                },
            )

        val details = sut.getBooleanDetails("flag", false, context)

        assertTrue(details.value)
        assertNull(details.errorCode)
        val options = capturedOptions().second
        assertEquals(mapOf<String, Any?>("optional" to null, "nested" to mapOf("plan" to null)), options.personProperties)
        assertNull(options.groups)
    }

    @Test
    fun `accepts null default values`() {
        getSut(flags = mapOf("flag" to false))
        val sut: FeatureProvider = PostHogProvider(posthog)

        assertEquals(false, sut.getBooleanEvaluation("flag", null, user()).value)
        assertNull(sut.getStringEvaluation("flag", null, user()).value)
        assertNull(sut.getIntegerEvaluation("flag", null, user()).value)
        assertNull(sut.getLongEvaluation("flag", null, user()).value)
        assertNull(sut.getDoubleEvaluation("flag", null, user()).value)
        assertNull(sut.getObjectEvaluation("flag", null, user()).value)
    }

    @Test
    fun `object returns the json payload`() {
        val sut =
            getSut(
                flags = mapOf("flag" to "test"),
                payloads = mapOf("flag" to """{"color":"blue","size":3,"ratio":0.5,"items":[1,true,null]}"""),
            )

        val details = sut.getObjectDetails("flag", Value(), user())

        val structure = details.value.asStructure()
        assertEquals("blue", structure.getValue("color").asString())
        assertEquals(3, structure.getValue("size").asInteger())
        assertEquals(0.5, structure.getValue("ratio").asDouble())
        val items = structure.getValue("items").asList()
        assertEquals(1, items[0].asInteger())
        assertTrue(items[1].asBoolean())
        assertTrue(items[2].isNull)
        assertEquals("test", details.variant)
        assertEquals(Reason.TARGETING_MATCH.name, details.reason)
    }

    @Test
    fun `object returns a json array payload`() {
        val sut = getSut(flags = mapOf("flag" to true), payloads = mapOf("flag" to """["a","b"]"""))

        val value = sut.getObjectValue("flag", Value(), user())

        assertEquals(listOf("a", "b"), value.asList().map { it.asString() })
    }

    @Test
    fun `object returns type mismatch without an object payload`() {
        val sut = getSut(flags = mapOf("none" to true, "scalar" to true), payloads = mapOf("scalar" to "\"text\""))
        val default = Value("default")

        val none = sut.getObjectDetails("none", default, user())
        val scalar = sut.getObjectDetails("scalar", default, user())

        assertEquals(ErrorCode.TYPE_MISMATCH, none.errorCode)
        assertEquals(ErrorCode.TYPE_MISMATCH, scalar.errorCode)
        assertEquals("default", scalar.value.asString())
    }

    @Test
    fun `object returns parse error for invalid json`() {
        val sut = getSut(flags = mapOf("flag" to true), payloads = mapOf("flag" to "{not json"))

        val details = sut.getObjectDetails("flag", Value("default"), user())

        assertEquals(ErrorCode.PARSE_ERROR, details.errorCode)
    }

    @Test
    fun `object returns parse error for deeply nested payloads`() {
        val payload = "[".repeat(10_000) + "]".repeat(10_000)
        val sut = getSut(flags = mapOf("flag" to true), payloads = mapOf("flag" to payload))

        val details = sut.getObjectDetails("flag", Value("default"), user())

        assertEquals(ErrorCode.PARSE_ERROR, details.errorCode)
        assertEquals("default", details.value.asString())
    }

    @Test
    fun `object converts large numbers without expanding them`() {
        val sut =
            getSut(
                flags = mapOf("flag" to true),
                payloads = mapOf("flag" to """{"huge":1e500000000,"long":9000000000,"int":-5}"""),
            )

        val structure = sut.getObjectValue("flag", Value(), user()).asStructure()

        assertEquals(Double.POSITIVE_INFINITY, structure.getValue("huge").asDouble())
        assertEquals(9_000_000_000L, structure.getValue("long").asLong())
        assertEquals(-5, structure.getValue("int").asInteger())
    }

    @Test
    fun `object returns the default when flag is off`() {
        val sut = getSut(flags = mapOf("flag" to false))

        val details = sut.getObjectDetails("flag", Value("default"), user())

        assertEquals("default", details.value.asString())
        assertNull(details.errorCode)
    }
}
