package com.posthog.server

import com.posthog.server.otel.PostHogContextSpanProcessor
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.SdkTracerProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(Parameterized::class)
internal class PostHogSpanPrivacyTest(private val state: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun states(): List<Array<String>> = listOf("enabled", "before", "during", "uninitialized", "closed").map { arrayOf(it) }
    }

    @Test
    fun `scope owner controls request and child enrichment independently`() {
        val owner = PostHog()
        val other = PostHog()
        val config = PostHogConfig("phc_test", preloadFeatureFlags = false)
        try {
            if (state != "uninitialized") owner.setup(config)
            other.setup(PostHogConfig("phc_other", preloadFeatureFlags = false))
            if (state == "before") owner.optOut()
            if (state == "closed") owner.close()
            SdkTracerProvider.builder()
                .addSpanProcessor(PostHogContextSpanProcessor(owner))
                .addSpanProcessor(PostHogContextSpanProcessor(other)).build().use { provider ->
                    val tracer = provider.get("privacy")
                    val incoming = tracer.spanBuilder("incoming").setAttribute("sessionId", false).startSpan() as ReadWriteSpan
                    incoming.makeCurrent().use {
                        PostHogRequestContext.withContext(owner, PostHogRequestContextData("owner-person", "owner-session"), fresh = true) {
                            val initial = if (state == "enabled" || state == "during") "owner-person" else null
                            assertEquals(initial, incoming.toSpanData().attributes.get(AttributeKey.stringKey("posthogDistinctId")))
                            assertEquals(false, incoming.toSpanData().attributes.get(AttributeKey.booleanKey("sessionId")))
                            if (state == "during") owner.optOut()
                            val child = tracer.spanBuilder("child").setAttribute("sessionId", false).startSpan() as ReadWriteSpan
                            child.makeCurrent().use {
                                PostHogRequestContext.beginScope(PostHogRequestContextData("later-person", "later-session")).use {
                                    val expected = if (state == "enabled") "owner-person" else null
                                    assertEquals(expected, child.toSpanData().attributes.get(AttributeKey.stringKey("posthogDistinctId")))
                                    assertEquals(false, child.toSpanData().attributes.get(AttributeKey.booleanKey("sessionId")))
                                }
                            }
                            child.end()
                            PostHogRequestContext.withContext(other, PostHogRequestContextData(distinctId = "other-person")) {
                                val separate = tracer.spanBuilder("other-owner").startSpan() as ReadWriteSpan
                                separate.end()
                                assertEquals(
                                    "other-person",
                                    separate.toSpanData().attributes.get(AttributeKey.stringKey("posthogDistinctId")),
                                )
                                assertNull(separate.toSpanData().attributes.get(AttributeKey.stringKey("sessionId")))
                            }
                            assertEquals("owner-session", PostHogRequestContext.current()?.sessionId)
                            val restored = tracer.spanBuilder("restored-owner").startSpan() as ReadWriteSpan
                            restored.end()
                            val expected = if (state == "enabled") "owner-session" else null
                            assertEquals(expected, restored.toSpanData().attributes.get(AttributeKey.stringKey("sessionId")))
                        }
                    }
                    incoming.end()
                    assertNull(PostHogRequestContext.current())
                }
        } finally {
            owner.close()
            other.close()
        }
    }
}
