package com.posthog.server

import com.posthog.PostHogEvent
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(Parameterized::class)
internal class PostHogExceptionTraceTest(
    private val name: String,
    private val context: SpanContext,
    private val callerProperties: Map<String, Any>,
    private val expectedIds: Map<String, Any>,
    private val requestProperties: Map<String, Any>,
) {
    companion object {
        private const val TRACE_ID = "00000000000000000000000000000003"
        private const val SPAN_ID = "0000000000000004"
        private val ids = mapOf("\$trace_id" to TRACE_ID, "\$span_id" to SPAN_ID)

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): Collection<Array<Any>> {
            val valid = SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getDefault(), TraceState.getDefault())
            val failing = mock<SpanContext>()
            whenever(failing.isValid).thenReturn(true)
            whenever(failing.traceId).thenThrow(IllegalStateException("Example lookup failure"))
            val none = emptyMap<String, Any>()
            return listOf(
                arrayOf("unsampled leading zeros", valid, none, ids, none),
                arrayOf(
                    "sampled",
                    SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault()),
                    none,
                    ids,
                    none,
                ),
                arrayOf("no span", SpanContext.getInvalid(), none, none, none),
                arrayOf(
                    "zero trace",
                    SpanContext.create("0".repeat(32), SPAN_ID, TraceFlags.getDefault(), TraceState.getDefault()),
                    none,
                    none,
                    none,
                ),
                arrayOf(
                    "zero span",
                    SpanContext.create(TRACE_ID, "0".repeat(16), TraceFlags.getDefault(), TraceState.getDefault()),
                    none,
                    none,
                    none,
                ),
                arrayOf("caller trace", valid, mapOf("\$trace_id" to "caller-trace"), ids + ("\$trace_id" to "caller-trace"), none),
                arrayOf("caller span", valid, mapOf("\$span_id" to "caller-span"), ids + ("\$span_id" to "caller-span"), none),
                arrayOf("failed lookup", failing, none, none, none),
                arrayOf("request context", valid, none, ids + ("\$trace_id" to "request-trace"), mapOf("\$trace_id" to "request-trace")),
            )
        }
    }

    @Test
    fun `exception captures read active trace context without retaining it`() {
        val captured = mutableListOf<PostHogEvent>()
        val config = PostHogConfig("example-key", host = "https://example.com")
        config.addBeforeSend { event ->
            captured.add(event)
            null
        }
        val client = PostHog.with(config)
        try {
            val properties = mapOf("example" to "kept") + callerProperties
            Span.wrap(context).makeCurrent().use {
                PostHogRequestContext.beginScope(PostHogRequestContextData(properties = requestProperties), fresh = true).use {
                    client.captureException(IllegalStateException("Example failure"), "example-user", properties)
                    client.captureException(
                        IllegalStateException("Example failure"),
                        "example-user",
                        PostHogCaptureOptions.builder().properties(properties).build(),
                    )
                }
                client.capture("example-user", "example action")
            }
            client.captureException(IllegalStateException("Outside span"), "example-user")

            assertEquals(4, captured.size)
            for (event in captured.take(2)) {
                assertEquals("\$exception", event.event)
                assertEquals("example-user", event.distinctId)
                assertEquals(expectedIds, event.properties?.filterKeys { it == "\$trace_id" || it == "\$span_id" })
                assertEquals("kept", event.properties?.get("example"))
            }
            for (event in captured.drop(2)) {
                assertFalse(event.properties?.containsKey("\$trace_id") == true)
                assertFalse(event.properties?.containsKey("\$span_id") == true)
            }
            assertEquals(mapOf("example" to "kept") + callerProperties, properties)
        } finally {
            client.close()
        }
    }
}
